/**
 *  Copyright 2016 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
 
package com.wordnik.api.client.model

import com.wordnik.swagger.runtime.annotations._

import scala.reflect.BeanProperty

import scala.collection.JavaConversions._


/**
 * 
 *
 * NOTE: This class is auto generated by the swagger code generator program. Do not edit the class manually.
 *
 * @author tony
 *
 */
class User extends Object {

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var id:Long =_

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var username:String =_

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var status:Integer =_

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var email:String =_

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var faceBookId:String =_

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var userName:String =_

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var displayName:String =_

	/**
	 * 
	 * 
	 * 
	 */
    @BeanProperty
    var password:String =_

    override def toString:String = {
        "[" +
        "id:" + id + 
            "username:" + username + 
            "status:" + status + 
            "email:" + email + 
            "faceBookId:" + faceBookId + 
            "userName:" + userName + 
            "displayName:" + displayName + 
            "password:" + password + "]"
    }
}