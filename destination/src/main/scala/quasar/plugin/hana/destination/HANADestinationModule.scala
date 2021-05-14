/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin.hana.destination

import scala._, Predef._
import scala.concurrent.duration._

import quasar.api.destination.DestinationType
import quasar.connector.MonadResourceErr
import quasar.connector.destination.{Destination, PushmiPullyu}
import quasar.lib.jdbc._
import quasar.lib.jdbc.destination._

import java.net.URI

import argonaut._, Argonaut._

import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import cats.implicits._

import doobie.Transactor

import org.slf4s.Logger

object HANADestinationModule extends DeferredJdbcDestinationModule[DestinationConfig] {

  val DefaultConnectionMaxConcurrency: Int = 8
  val DefaultConnectionMaxLifetime: FiniteDuration = 5.minutes

  val destinationType = DestinationType("sap-hana", 1L)

  def sanitizeDestinationConfig(config: Json): Json =
    config.as[DestinationConfig].toOption.fold(jEmptyObject)(_.sanitized.asJson)

  def transactorConfig(config: DestinationConfig)
      : Either[NonEmptyList[String], TransactorConfig] =
    for {
      cc <- config.connectionConfig.validated.toEither.leftMap(NonEmptyList.one(_))

      maxConcurrency = cc.maxConcurrency getOrElse DefaultConnectionMaxConcurrency
      maxLifetime = cc.maxLifetime getOrElse DefaultConnectionMaxLifetime

      jdbcUrl <- Either.catchNonFatal(URI.create(cc.jdbcUrl)).leftMap(_ => NonEmptyList.one(
        "Malformed JDBC connection string, ensure any restricted characters are properly escaped"))

      tc =
        TransactorConfig
          .withDefaultTimeouts(
            JdbcDriverConfig.JdbcDriverManagerConfig(jdbcUrl, Some("com.sap.db.jdbc.Driver")),
            connectionMaxConcurrency = maxConcurrency,
            connectionReadOnly = false)
      txConfig =
        tc.copy(poolConfig = tc.poolConfig.map(_.copy(connectionMaxLifetime = maxLifetime)))
    } yield txConfig

  def jdbcDestination[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
      config: DestinationConfig,
      transactor: Resource[F, Transactor[F]],
      pushPull: PushmiPullyu[F],
      log: Logger)
      : Resource[F, Either[InitError, Destination[F]]] = {
    val destination: Destination[F] = new HANADestination(
      config.writeMode,
      transactor,
      config.maxReattempts,
      config.retryTimeout,
      log)
    destination.asRight[InitError].pure[Resource[F, *]]
  }
}
