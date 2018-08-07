package io.predix.dcosb.util

import com.github.nscala_time.time.Imports
import com.github.nscala_time.time.Imports._
import org.joda.time.chrono.ISOChronology
import spray.json.{DeserializationException, JsString, JsValue, RootJsonFormat}

object JsonFormats {


  /**
    * Based on the code found: https://groups.google.com/forum/#!topic/spray-user/RkIwRIXzDDc
    */
  class EnumJsonConverter[T <: scala.Enumeration](enu: T) extends RootJsonFormat[T#Value] {
    override def write(obj: T#Value): JsValue = JsString(obj.toString)

    override def read(json: JsValue): T#Value = {
      json match {
        case JsString(txt) => enu.withName(txt)
        case somethingElse => throw DeserializationException(s"Expected a value from enum $enu instead of $somethingElse")
      }
    }
  }

  class DateTimeFormat extends RootJsonFormat[DateTime] {

    override def write(obj: Imports.DateTime): JsValue = JsString(obj.toString())

    override def read(json: JsValue): Imports.DateTime = {
      json match {
        case JsString(d: String) =>
          try {
            DateTime.parse(d).withChronology(ISOChronology.getInstance(DateTimeZone.getDefault()))
          } catch {
            case e: Throwable => throw DeserializationException(s"Failed to parse $d as a DateTime object", e)
          }

        case e => throw DeserializationException(s"Expected a string representation of a date, found $e")
      }

    }
  }

}
