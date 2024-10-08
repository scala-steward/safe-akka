package com.evolutiongaming.safeakka.persistence

import akka.actor.Actor
import akka.persistence as ap
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.*

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


trait SafePersistentActor[S, SS, C, E] extends ap.PersistentActor

object SafePersistentActor {

  def apply[S: Unapply, SS, C: Unapply, E: Unapply](setup: SetupPersistentActor[S, SS, C, E]): SafePersistentActor[S, SS, C, E] = {

    val setupArg = setup

    new SafePersistentActor[S, SS, C, E] with PersistentLike[S, SS, C, E] {

      override val asS: Unapply[S] = implicitly[Unapply[S]]

      override val asC: Unapply[C] = implicitly[Unapply[C]]

      override val asE: Unapply[E] = implicitly[Unapply[E]]

      override val journaller: Journaller = Journaller(this)

      override val snapshotter: Snapshotter[S] = Snapshotter[S](this)

      override def setupPersistentActor: SetupPersistentActor[S, SS, C, E] = setupArg

      override def journalPluginId: String = persistenceSetup.journalId getOrElse super.journalPluginId

      override def snapshotPluginId: String = persistenceSetup.snapshotId getOrElse super.snapshotPluginId

      override def lastSeqNr(): SeqNr = lastSequenceNr

      override def receiveRecover: Receive = rcvRecover orElse {
        case msg => onUnhandled(msg, "receiveRecover")
      }

      override def receiveCommand: Receive = rcvCommand

      override def onPersistFailure(cause: Throwable, event: Any, seqNr: SeqNr): Unit = {
        try {
          onPersistFailure(cause, lastSeqNr())
        } catch {
          case NonFatal(failure) => log.error(s"[$seqNr] onPersistFailure failed $failure", failure)
        }
        super.onPersistFailure(cause, event, seqNr)
      }

      override def onPersistRejected(cause: Throwable, event: Any, seqNr: SeqNr): Unit = {
        try {
          onPersistRejected(cause, lastSeqNr())
        } catch {
          case NonFatal(failure) => log.error(s"[$seqNr] onPersistRejected failed $failure", failure)
        }
        super.onPersistRejected(cause, event, seqNr)
      }

      override def onPersist(behavior: PersistentBehavior.Persist[C, E], onStop: SeqNr => Unit): Phase.Persisting = {
        val records = behavior.records
        val events = records.map(_.event)
        persistAll(events.toList) { _ =>
          onPersisted(Success(lastSeqNr()), lastSeqNr())
        }
        Phase.Persisting(
          records.map(_.onPersisted),
          onStop,
          behavior.onPersisted,
          behavior.onFailure)
      }

      def onPersistFailure(cause: Throwable, seqNr: SeqNr): Unit = {
        phase map {
          case phase: Phase.Persisting =>
            val failure = Failure(cause)
            phase.onEventPersisted.foreach(_.apply(failure))
            phase.onFailure(cause)
            Phase.Stopping(phase.onStop)
          case phase                   => invalid(phase, seqNr)
        }
      }

      def onPersistRejected(cause: Throwable, seqNr: SeqNr): Unit = {
        onPersisted(Failure(cause), seqNr)
      }

      def onPersisted(seqNr: Try[SeqNr], seqNrLast: SeqNr): Unit = {
        phase map {
          case phase: Phase.Persisting =>
            phase.onEventPersisted.head(seqNr)
            phase.onEventPersisted.tail match {
              case head :: tail => phase.copy(onEventPersisted = Nel(head, tail))
              case Nil          => seqNr match {
                case Success(seqNr)   => nextPhase(phase.onPersisted(seqNr), phase.onStop)
                case Failure(failure) =>
                  phase.onFailure(failure)
                  context.stop(self)
                  Phase.Stopping(phase.onStop)
              }
            }
          case phase                   => invalid(phase, seqNrLast)
        }
      }
    }
  }


  private[persistence] trait PersistentLike[S, SS, C, E] extends Actor {

    val asS: Unapply[S]
    val asC: Unapply[C]
    val asE: Unapply[E]

    def setupPersistentActor: SetupPersistentActor[S, SS, C, E]

    def lastSeqNr(): SeqNr

    def journaller: Journaller

    def snapshotter: Snapshotter[S]

    lazy val (persistenceSetup, phase) = {
      val ctx = ActorCtx(context)
      val setup = setupPersistentActor(ctx)
      val phase = Phase.ReadingSnapshot(setup.onRecoveryStarted(_, journaller, snapshotter), setup.onStopped)
      (setup, VarCover[Phase](phase))
    }

    def persistenceId: String = persistenceSetup.persistenceId

    def log: ActorLog = persistenceSetup.log

    def rcvRecover: Receive = {
      case asE(event)                      => onEvent(event, lastSeqNr())
      case ap.SnapshotOffer(m, asS(state)) => onSnapshotOffer(SnapshotOffer(m, state))
      case ap.SnapshotOffer(m, snapshot)   => sys.error(s"$persistenceId [${m.sequenceNr}] unexpected snapshot $snapshot")
      case ap.RecoveryCompleted            => onRecoveryCompleted(lastSeqNr())
    }

    def rcvCommand: Receive = RcvSystem(onSystem) orElse {
      case asC(cmd)                    => onCmd(PersistenceSignal.Cmd(cmd, sender()), lastSeqNr())
      case ap.SnapshotResponse(signal) => onCmd(signal, lastSeqNr())
      case ap.EventsResponse(signal)   => onCmd(signal, lastSeqNr())
      case msg                         => onAny(msg, lastSeqNr(), sender())
    }

    override def postStop(): Unit = {
      onSystem(Signal.PostStop)
      super.postStop()
    }

    override def postRestart(reason: Throwable): Unit = {
      super.postRestart(reason)
      onSystem(Signal.PostRestart(reason))
    }

    override def toString: String = s"SafePersistentActor($persistenceId)"

    def onSystem(signal: Signal.System): Unit = {
      onSystem(signal, lastSeqNr())
    }

    def onSystem(signal: Signal.System, seqNr: SeqNr): Unit = {
      log.debug(s"[$seqNr] onSystem $signal")

      def unexpected(phase: Phase) = sys.error(s"$persistenceId [$seqNr] unexpected signal $signal in phase $phase")

      phase map {
        case phase: Phase.Receiving =>
          val nextPhase = this.nextPhase(phase, PersistenceSignal.Sys(signal), seqNr)
          signal match {
            case Signal.PostStop => Phase.Final
            case _               => nextPhase
          }

        case phase: Phase.ReadingEvents => signal match {
          case Signal.PostStop       => phase.onStop(phase.state, seqNr); Phase.Final
          case _: Signal.PostRestart => phase
          case _                     => unexpected(phase)
        }

        case phase: Phase.ReadingSnapshot => signal match {
          case Signal.PostStop       => phase.onStop(seqNr); Phase.Final
          case _: Signal.PostRestart => phase
          case _                     => unexpected(phase)
        }

        case phase: Phase.Stopping => signal match {
          case Signal.PostStop => phase.onStop(seqNr); Phase.Final
          case _               => unexpected(phase)
        }

        case phase: Phase.Persisting => signal match {
          case Signal.PostStop => phase.onStop(seqNr); Phase.Final
          case _               => unexpected(phase)
        }

        case phase => invalid(phase, seqNr)
      }
    }

    def onUnhandled(msg: Any, name: => String): Unit = {
      log.warn {
        if (sender() == context.system.deadLetters) {
          s"$name unhandled message: ${ msg.getClass.getName }"
        } else {
          s"$name unhandled message: ${ msg.getClass.getName }, sender: ${ sender() }"
        }
      }
      unhandled(msg)
    }

    def onSnapshotOffer(snapshot: SnapshotOffer[S]): Unit = {
      log.debug(s"[${ snapshot.metadata.sequenceNr }] onSnapshotOffer $snapshot")

      phase map {
        case phase: Phase.ReadingSnapshot =>
          val recovering = phase.onRecoveryStarted(Some(snapshot))
          Phase.ReadingEvents(
            recovering.state,
            recovering.eventHandler,
            recovering.onCompleted,
            recovering.onStopped)

        case phase => invalid(phase, snapshot.metadata.sequenceNr)
      }
    }

    def onEvent(event: E, seqNr: SeqNr): Unit = {
      log.debug(s"[$seqNr] onEvent $event")

      phase map {
        case phase: Phase.ReadingEvents =>
          val state = phase.eventHandler(phase.state, event, seqNr)
          phase.copy(state = state)

        case phase: Phase.ReadingSnapshot =>
          val recovering = phase.onRecoveryStarted(None)
          val eventHandler = recovering.eventHandler _
          val state = eventHandler(recovering.state, event, seqNr)
          Phase.ReadingEvents(state, eventHandler, recovering.onCompleted, recovering.onStopped)

        case phase => invalid(phase, seqNr)
      }
    }

    def onRecoveryCompleted(seqNr: SeqNr): Unit = {

      def apply(state: SS, onRecoveryCompleted: (SS, SeqNr) => PersistentBehavior[C, E], onStop: SeqNr => Unit) = {
        log.debug(s"[$seqNr] onRecoveryCompleted $state")
        val behavior = onRecoveryCompleted(state, seqNr)
        nextPhase(behavior, onStop)
      }

      phase map {
        case phase: Phase.ReadingEvents =>
          val onStop = phase.onStop(phase.state, _: SeqNr)
          apply(phase.state, phase.onCompleted, onStop)

        case phase: Phase.ReadingSnapshot =>
          val recovering = phase.onRecoveryStarted(None)
          apply(recovering.state, recovering.onCompleted, phase.onStop)

        case phase => invalid(phase, seqNr)
      }
    }


    protected def onCmd(signal: PersistenceSignal[C], seqNr: SeqNr): Unit = {

      def logError(): Unit = log.error(s"[$seqNr] onCmd $signal")

      signal match {
        case _: PersistenceSignal.SaveSnapshotFailure    => logError()
        case _: PersistenceSignal.DeleteSnapshotFailure  => logError()
        case _: PersistenceSignal.DeleteSnapshotsFailure => logError()
        case _: PersistenceSignal.DeleteEventsFailure    => logError()
        case _                                           => log.debug(s"[$seqNr] onCmd $signal")
      }

      phase map {
        case phase: Phase.Receiving => nextPhase(phase, signal, seqNr)
        case phase                  => invalid(phase, seqNr)
      }
    }

    protected def onAny(msg: Any, seqNr: SeqNr, sender: Sender): Unit = {
      phase map {
        case Phase.Receiving(behavior) if behavior.onAny(seqNr, sender).isDefinedAt(msg) =>
          log.debug {
            val senderStr = if (sender == context.system.deadLetters) "" else s", sender: $sender"
            s"[$seqNr] onAny $msg$senderStr"
          }

          val next = behavior.onAny(seqNr, sender)(msg)
          val onSignal = (seqNr: SeqNr) => {
            behavior.onSignal(PersistenceSignal.Sys(Signal.PostStop), seqNr)
            ()
          }
          nextPhase(next, onSignal)


        case phase =>
          onUnhandled(msg, s"[$seqNr] onCmd")
          phase
      }
    }

    def nextPhase(phase: Phase.Receiving, signal: PersistenceSignal[C], seqNr: SeqNr): Phase = {
      val behavior = phase.behavior
      val onSignal = (seqNr: SeqNr) => {
        behavior.onSignal(PersistenceSignal.Sys(Signal.PostStop), seqNr)
        ()
      }
      nextPhase(behavior.onSignal(signal, seqNr), onSignal)
    }

    def nextPhase(behavior: PersistentBehavior[C, E], onStop: SeqNr => Unit): Phase = behavior match {
      case behavior: PersistentBehavior.Rcv[C, E]     => Phase.Receiving(behavior)
      case behavior: PersistentBehavior.Persist[C, E] => onPersist(behavior, onStop)
      case PersistentBehavior.Stop                    =>
        context stop self
        Phase.Stopping(onStop)
    }

    def onPersist(behavior: PersistentBehavior.Persist[C, E], onStop: SeqNr => Unit): Phase

    def invalid(phase: Phase, seqNr: SeqNr): Nothing = {
      sys.error(s"$persistenceId [$seqNr] invalid phase $phase")
    }

    sealed trait Phase extends Product {
      override def toString: String = productPrefix
    }

    object Phase {

      case class ReadingSnapshot(
        onRecoveryStarted: Option[SnapshotOffer[S]] => Recovering[SS, C, E],
        onStop: SeqNr => Unit) extends Phase

      case class ReadingEvents(
        state: SS,
        eventHandler: EventHandler[SS, E],
        onCompleted: (SS, SeqNr) => PersistentBehavior[C, E],
        onStop: (SS, SeqNr) => Unit) extends Phase

      case class Receiving(behavior: PersistentBehavior.Rcv[C, E]) extends Phase

      case class Stopping(onStop: SeqNr => Unit) extends Phase

      case class Persisting(
        onEventPersisted: Nel[Try[SeqNr] => Unit],
        onStop: SeqNr => Unit,
        onPersisted: SeqNr => PersistentBehavior[C, E],
        onFailure: Throwable => Unit) extends Phase

      case object Final extends Phase
    }
  }
}