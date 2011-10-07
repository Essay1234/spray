/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray
package marshalling

import http._
import MediaTypes._
import MediaRanges._
import HttpCharsets._
import xml.{XML, NodeSeq}
import java.nio.ByteBuffer
import java.net.URLDecoder
import utils.FormContent
import java.io.ByteArrayInputStream

trait DefaultUnmarshallers extends MultipartUnmarshallers {
  
  implicit val StringUnmarshaller: Unmarshaller[String] = {
    case Some(content) => Right { // we can convert anything to a String
      new String(content.buffer, content.contentType.charset.getOrElse(`ISO-8859-1`).nioCharset)
    }
    case None => Left(ContentExpected)
  }

  implicit val CharArrayUnmarshaller: Unmarshaller[Array[Char]] = {
    case Some(content) => Right { // we can convert anything to a char array
      val nioCharset = content.contentType.charset.getOrElse(`ISO-8859-1`).nioCharset
      val byteBuffer = ByteBuffer.wrap(content.buffer)
      val charBuffer = nioCharset.decode(byteBuffer)
      charBuffer.array()
    }
    case None => Left(ContentExpected)
  }
  
  implicit val NodeSeqUnmarshaller = new UnmarshallerBase[NodeSeq] {
    val canUnmarshalFrom = ContentTypeRange(`text/xml`) ::
                           ContentTypeRange(`text/html`) ::
                           ContentTypeRange(`application/xhtml+xml`) :: Nil

    def unmarshal(content: HttpContent) = protect {
      if (content.contentType.charset.isDefined) {
        XML.loadString(StringUnmarshaller(Some(content)).right.get)
      } else {
        XML.load(new ByteArrayInputStream(content.buffer))
      }
    }
  }

  implicit val FormContentUnmarshaller = new UnmarshallerBase[FormContent] {
    val canUnmarshalFrom = ContentTypeRange(`application/x-www-form-urlencoded`) :: Nil
  
    def unmarshal(content: HttpContent) = protect {
      FormContent {
        val data = DefaultUnmarshallers.StringUnmarshaller(Some(content)).right.get
        val charset = content.contentType.charset.getOrElse(`ISO-8859-1`).aliases.head
        URLDecoder.decode(data, charset).fastSplit('&').map {
          _.fastSplit('=') match {
            case key :: value :: Nil => (key, value)
            case _ => throw new IllegalArgumentException("'" + data + "' is not a valid form content")
          }
        } (collection.breakOut)
      }
    }
  }

  implicit def optionUnmarshaller[A :Unmarshaller]: Unmarshaller[Option[A]] = {
    case x: Some[HttpContent] => unmarshaller[A].apply(x) match {
      case Right(a) => Right(Some(a))
      case Left(error) => Left(error)
    }
    case None => Right(None)
  }
  
  implicit def pimpHttpContentWithAs1(c: HttpContent): HttpContentExtractor = new HttpContentExtractor(Some(c)) 
  implicit def pimpHttpContentWithAs2(c: Option[HttpContent]): HttpContentExtractor = new HttpContentExtractor(c)
  
  class HttpContentExtractor(content: Option[HttpContent]) {
    def as[A](implicit unmarshaller: Unmarshaller[A]): Either[UnmarshallingError, A] = unmarshaller(content)
  }
  
}

object DefaultUnmarshallers extends DefaultUnmarshallers