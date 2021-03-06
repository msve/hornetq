/*
 * Copyright 2005-2014 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.core.protocol.core;


/**
 * A CommandConfirmationHandler is used by the channel to confirm confirmations of packets.
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *         <p/>
 *         Created 9 Feb 2009 12:39:11
 */
public interface CommandConfirmationHandler
{
   /**
    * called by channel after a confirmation has been received.
    *
    * @param packet the packet confirmed
    */
   void commandConfirmed(Packet packet);
}
