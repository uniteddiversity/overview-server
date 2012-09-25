package models.orm

import java.sql.Timestamp
import models.orm.Dsl.{ crypt, gen_hash }
import org.joda.time.DateTime.now
import org.squeryl.annotations.{ Column, Transient }
import org.squeryl.dsl.{ ManyToMany, OneToMany }
import org.squeryl.KeyedEntity
import org.squeryl.PrimitiveTypeMode._
import scala.annotation.target.field
import ua.t3hnar.bcrypt._

case class User(
  val email: String = "user@example.org",
  @Column("password_hash") var passwordHash: String = "",
  var role: UserRole.UserRole = UserRole.NormalUser,
  @Column("confirmation_token") var confirmationToken: Option[String] = None,
  @Column("confirmation_sent_at") var confirmationSentAt: Option[Timestamp] = None,
  @Column("confirmed_at") var confirmedAt: Option[Timestamp] = None //@Column("reset_password_token")
  //var resetPasswordToken: Option[String],
  //@Column("reset_password_sent_at")
  //var resetPasswordSentAt: Option[DateTime],
  //@Column("current_sign_in_at")
  //var currentSignInAt: Option[DateTime],
  //@Column("current_sign_in_ip")
  //var currentSignInIp: Option[String],
  //@Column("last_sign_in_at")
  //var lastSignInAt: Option[DateTime],
  //@Column("last_sign_in_ip")
  //var lastSignInIp: Option[String]
  ) extends KeyedEntity[Long] {

  val id: Long = 0l

  def this() = this(role = UserRole.NormalUser)

  lazy val documentSets: ManyToMany[DocumentSet, DocumentSetUser] =
    Schema.documentSetUsers.right(this)

  def createDocumentSet(query: String): DocumentSet = {
    require(id != 0l)

    val documentSet = Schema.documentSets.insert(new DocumentSet(0L, query))
    documentSets.associate(documentSet)

    documentSet
  }

  def save = {
    Schema.users.insertOrUpdate(this)
  }
}

object User {
  private val TokenLength = 26
  private val BcryptRounds = 7

  def findById(id: Long) = Schema.users.lookup(id)

  def findByEmail(email: String): Option[User] = {
    Schema.users.where(u => lower(u.email) === lower(email)).headOption
  }

  def findByConfirmationToken(token: String): Option[User] = {
    Schema.users.where(u => u.confirmationToken.getOrElse("") === token).headOption
  }
}
