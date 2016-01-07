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

import scala.collection.mutable.ListBuffer


/**
 * 
 *
 * NOTE: This class is auto generated by the swagger code generator program. Do not edit the class manually.
 *
 * @author tony
 *
 */
class ExampleSearchResults extends Object {

	/**
	 * 
	 * 
	 * 
	 */
	var facets  =  new ListBuffer[Facet]
	def getFacets:java.util.List[com.wordnik.api.client.model.Facet] = {
	    facets.toList
	}
	def setFacets(args:java.util.List[com.wordnik.api.client.model.Facet]) = {
	    facets.clear
	    args.foreach(arg=>facets += arg)
	}

	/**
	 * 
	 * 
	 * 
	 */
	var examples  =  new ListBuffer[Example]
	def getExamples:java.util.List[com.wordnik.api.client.model.Example] = {
	    examples.toList
	}
	def setExamples(args:java.util.List[com.wordnik.api.client.model.Example]) = {
	    examples.clear
	    args.foreach(arg=>examples += arg)
	}

    override def toString:String = {
        "[" +
        "facets:" + facets + 
            "examples:" + examples + "]"
    }
}