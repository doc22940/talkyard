/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import org.scalatest._
import org.scalatestplus.play.OneAppPerSuite
import java.{util => ju, io => jio}
import play.api.test.FakeApplication


class DaoAppSuite extends FreeSpec with MustMatchers with OneAppPerSuite {

  implicit override lazy val app = FakeApplication(
    additionalConfiguration = Map("isTestShallEmptyDatabase" -> "true"))

  def browserIdData = BrowserIdData("1.2.3.4", idCookie = "dummy_id_cookie", fingerprint = 334455)


  /** Its name will be "User $password", username "user_$password" and email "user-$password@x.c",
    */
  def createPasswordUser(password: String, dao: SiteDao): User = {
    dao.createPasswordUserCheckPasswordStrong(NewPasswordUserData.create(
      name = s"User $password", username = s"user_$password", email = s"user-$password@x.c",
      password = password, isAdmin = false, isOwner = false).get)
  }

}
