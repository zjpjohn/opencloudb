/*
 * Copyright 2012-2015 org.opencloudb.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencloudb.net.postgres;

/**
 * <pre>
 * BindComplete (B) 
 * Byte1('2') Identifies the message as a Bind-complete indicator. 
 * Int32(4) Length of message contents in bytes, including self.
 * </pre>
 * 
 * @author mycat
 */
public class BindComplete extends PostgresPacket {

}