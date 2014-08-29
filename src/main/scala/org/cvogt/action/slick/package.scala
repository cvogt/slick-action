package org.cvogt.action
package object slick{
  import scala.slick.driver.JdbcDriver
  /** Creates an instance of the Slick Action extension */
  def SlickActionExtension[P <: JdbcDriver](driver: P)
    = new SlickActionExtension(driver)
}