/*
 * MIT License
 *
 * Copyright (c) 2016 Gonçalo Marques
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.byteslounge.slickrepo.repository

import com.byteslounge.slickrepo.meta.{Entity, Keyed}
import slick.ast.BaseTypedType
import com.byteslounge.slickrepo.scalaversion.JdbcProfile
import com.byteslounge.slickrepo.scalaversion.RelationalProfile
import slick.lifted.AppliedCompiledFunction

import scala.concurrent.ExecutionContext

/**
 * Repository used to execute CRUD operations against a database for
 * a given entity type.
 */
abstract class Repository[T <: Entity[T, ID], ID](val driver: JdbcProfile) {

  import driver.api._

  type TableType <: Keyed[ID] with RelationalProfile#Table[T]
  def pkType: BaseTypedType[ID]
  implicit lazy val _pkType: BaseTypedType[ID] = pkType
  def tableQuery: TableQuery[TableType]

  /**
  * Finds all entities.
  */
  def findAll(): DBIO[Seq[T]] = {
    tableQueryCompiled.result
  }

  /**
  * Finds a given entity by its primary key.
  */
  def findOne(id: ID): DBIO[Option[T]] = {
    findOneCompiled(id).result.headOption
  }

  /**
  * Locks an entity using a pessimistic lock.
  */
  def lock(entity: T)(implicit ec: ExecutionContext): DBIO[T] = {
    val result = findOneCompiled(entity.id.get).result
    result.overrideStatements(
      Seq(exclusiveLockStatement(result.statements.head))
    ).map(_ => entity)
  }

  /**
  * Persists an entity for the first time.
  *
  * If the entity has an already assigned primary key, then it will
  * be persisted with that same primary key.
  *
  * If the entity doesn't have an already assigned primary key, then
  * it will be persisted using an auto-generated primary key using
  * the generation strategy configured in the entity definition.
  *
  * A new entity with the primary key assigned to it will be returned.
  */
  def save(entity: T)(implicit ec: ExecutionContext): DBIO[T] = {
    entity.id match {
      case None    => saveUsingGeneratedId(entity)
      case Some(_) => saveUsingPredefinedId(entity)
    }
  }

  /**
  * Persists an entity using an auto-generated primary key.
  */
  private[repository] def saveUsingGeneratedId(entity: T)(implicit ec: ExecutionContext): DBIO[T] = {
    saveUsingGeneratedId(() => entity)
  }

  /**
  * Helper used while saving an entity with a generated id.
  */
  private[repository] def saveUsingGeneratedId(supplier: () => T)(implicit ec: ExecutionContext): DBIO[T] = {
    val entity: T = supplier.apply()
    (saveCompiled += entity).map(id => entity.withId(id))
  }

  /**
  * Persists an entity using a predefined primary key.
  */
  private[repository] def saveUsingPredefinedId(entity: T)(implicit ec: ExecutionContext): DBIO[T] = {
    saveUsingPredefinedId(() => entity)
  }

  /**
  * Helper used while saving an entity with a predefined id.
  */
  private[repository] def saveUsingPredefinedId(supplier: () => T)(implicit ec: ExecutionContext): DBIO[T] = {
    val entity: T = supplier.apply()
    (tableQueryCompiled += entity).map(_ => entity)
  }

  /**
  * Performs a batch insert of the entities that are passed in
  * as an argument. The result will be the number of created
  * entities in case of a successful batch insert execution
  * (if the row count is provided by the underlying database
  * or driver. If not, then `None` will be returned as the
  * result of a successful batch insert operation).
  */
  def batchInsert(entities: Seq[T]): DBIO[Option[Int]] = {
    batchInsert(() => entities)
  }

  /**
  * Helper used in batch insert.
  */
  private[repository] def batchInsert(supplier: () => Seq[T]): DBIO[Option[Int]] = {
    tableQueryCompiled ++= supplier.apply()
  }

  /**
  * Updates a given entity in the database.
  *
  * If the entity is not yet persisted in the database then
  * this operation will result in an exception being thrown.
  *
  * Returns the same entity instance that was passed in as
  * an argument.
  */
  def update(entity: T)(implicit ec: ExecutionContext): DBIO[T] = {
    update(findOneCompiled(entity.id.get), entity, _ => entity)
  }

  /**
  * Helper used to update an entity.
  */
  private[repository] def update(finder: AppliedCompiledFunction[_, Query[TableType, T, Seq], Seq[T]], entity: T, mapper: Int => T)(implicit ec: ExecutionContext): DBIO[T] = {
    finder.update(entity).map(mapper)
  }

  /**
  * Deletes a given entity from the database.
  *
  * If the entity is not yet persisted in the database then
  * this operation will result in an exception being thrown.
  */
  def delete(id: ID): DBIO[Int] = {
    findOneCompiled(id).delete
  }

  /**
  * Counts all entities.
  */
  def count(): DBIO[Int] = {
    countCompiled.result
  }

  /**
  * Executes the given unit of work in a single transaction.
  */
  def executeTransactionally[R](work: DBIO[R]): DBIO[R] = {
    work.transactionally
  }

  /**
  * Returns the pessimistic lock statement based on the
  * current database driver type.
  */
  def exclusiveLockStatement(sql: String): String = {
    driver.getClass.getSimpleName.toLowerCase match {
      case n: String if n.contains("db2") || n.contains("derby") => sql + " FOR UPDATE WITH RS"
      case n: String if n.contains("sqlserver") => sql.replaceFirst(" where ", " WITH (UPDLOCK, ROWLOCK) WHERE ")
      case _: String => sql + " FOR UPDATE"
    }
  }

  lazy protected val tableQueryCompiled = Compiled(tableQuery)
  lazy protected val findOneCompiled = Compiled((id: Rep[ID]) => tableQuery.filter(_.id === id))
  lazy protected val saveCompiled = tableQuery returning tableQuery.map(_.id)
  lazy private val countCompiled = Compiled(tableQuery.map(_.id).length)

}
