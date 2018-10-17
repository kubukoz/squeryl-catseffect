package com.kubukoz.squeryl

import cats.FlatMap
import cats.effect.syntax.all._
import cats.effect.{Bracket, Resource, Sync}
import cats.implicits._
import cats.tagless.finalAlg
import com.kubukoz.squeryl
import javax.sql.DataSource
import org.squeryl.{AbstractSession, Session}
import org.squeryl.dsl.QueryDsl
import org.squeryl.internals.DatabaseAdapter

@finalAlg
trait SessionFactoryAlg[F[_]] {
  def newSession: F[PureSession[F]]
  //utility - not directly connected with squeryl
  def inNewSession[A](fa: F[A]): F[A] // = newSession.flatMap(_.withinTransaction(fa))
}

//corresponds to an `AbstractSession`
@finalAlg
trait PureSession[F[_]] {
  def using[A](fa: F[A]): F[A]
  def withinTransaction[A](fa: F[A]): F[A]
  def unbindFromCurrentThread: F[Unit]
  def bindToCurrentThread: F[Unit]

  private[squeryl] def cleanup: F[Unit]
}

object PureSession {

  def unsafeFromAbstractSession[F[_]: Sync: SessionAlg](underlying: F[AbstractSession]): F[PureSession[F]] =
    underlying.map { abs =>
      new PureSession[F] { self =>
        override def bindToCurrentThread: F[Unit]     = Sync[F].delay(abs.bindToCurrentThread)
        override def unbindFromCurrentThread: F[Unit] = Sync[F].delay(abs.unbindFromCurrentThread)

        /**
          * Adapted from AbstractSession#using
          * */
        override def using[A](fa: F[A]): F[A] = {
          SessionAlg[F].currentSessionOption.flatMap { currentSessionOpt =>
            val currentResource: Resource[F, Unit] = currentSessionOpt.traverse_ { ses =>
              Resource.make(ses.unbindFromCurrentThread)(_ => ses.bindToCurrentThread)
            }

            val thisResource = Resource.make(bindToCurrentThread)(_ => unbindFromCurrentThread *> cleanup)

            val currentAndThis: Resource[F, Unit] = currentResource *> thisResource

            currentAndThis.use(_ => fa)
          }
        }

        override def withinTransaction[A](fa: F[A]): F[A] = ??? //no idea man

        override private[squeryl] def cleanup: F[Unit] = Sync[F].delay(abs.cleanup)
      }
    }
}

//corresponds to `object Session`
@finalAlg
trait SessionAlg[F[_]] {
  def hasCurrentSession: F[Boolean]
  def currentSession: F[PureSession[F]]
  def currentSessionOption: F[Option[PureSession[F]]]
}

object SessionAlg {

  def unsafeDerive[F[_]: Sync]: SessionAlg[F] = new SessionAlg[F] { self =>
    override val hasCurrentSession: F[Boolean] = Sync[F].delay(Session.hasCurrentSession)
    override val currentSession: F[PureSession[F]] = {
      val current = Sync[F].delay(Session.currentSession)

      Sync[F].suspend {
        implicit val sessions: SessionAlg[F] = self
        PureSession.unsafeFromAbstractSession(current)
      }
    }
    override val currentSessionOption: F[Option[PureSession[F]]] = {
      val current = Sync[F].delay(Session.currentSessionOption)

      current.flatMap {
        _.traverse { ses =>
          implicit val sessions: SessionAlg[F] = self
          PureSession.unsafeFromAbstractSession(ses.pure[F])
        }
      }
    }
  }

}

//todo make this an algebra
object TransactionDsl {
  object dsl extends QueryDsl

  type BracketK[E] = { type λ[F[_]] = Bracket[F, E] }

  def transaction[F[_]: SessionFactoryAlg: SessionAlg: BracketK[E]#λ, A, E](fa: F[A]): F[A] = {
    val inNew = SessionFactoryAlg[F].inNewSession(fa)

    SessionAlg[F].hasCurrentSession.ifM(
      inNew,
      SessionAlg[F].currentSession.bracket(_.unbindFromCurrentThread *> inNew)(_.bindToCurrentThread)
    )
  }

  def inTransaction[F[_]: FlatMap: SessionFactoryAlg: SessionAlg, A](fa: F[A]): F[A] = {
    SessionAlg[F].hasCurrentSession.ifM(fa, squeryl.SessionFactoryAlg[F].inNewSession(fa))
  }
}
