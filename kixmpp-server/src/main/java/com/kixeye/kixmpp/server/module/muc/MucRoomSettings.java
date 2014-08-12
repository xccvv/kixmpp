package com.kixeye.kixmpp.server.module.muc;

/*
 * #%L
 * KIXMPP
 * %%
 * Copyright (C) 2014 KIXEYE, Inc
 * %%
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
 * #L%
 */

/**
 * Settings for {@link MucRoom}
 *
 * @author dturner@kixeye.com
 */
public class MucRoomSettings {

    private boolean isOpen = true;
    private String subject = null;

    public MucRoomSettings(MucRoomSettings settings) {
    	isOpen = settings.isOpen();
    }

    public MucRoomSettings() {
    }

    public MucRoomSettings isOpen(boolean isOpen){
        this.isOpen = isOpen;
        return this;
    }

    public MucRoomSettings subject(String subject){
        this.subject = subject;
        return this;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public String getSubject() {
        return subject;
    }
}
