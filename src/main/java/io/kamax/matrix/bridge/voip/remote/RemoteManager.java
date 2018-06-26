/*
 * matrix-appservice-voip - Matrix Bridge to VoIP/SMS
 * Copyright (C) 2018 Kamax Sarl
 *
 * https://www.kamax.io/
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

package io.kamax.matrix.bridge.voip.remote;

import io.kamax.matrix.bridge.voip.CallHangupEvent;
import io.kamax.matrix.bridge.voip.CallInviteEvent;
import io.kamax.matrix.bridge.voip.remote.call.FreeswitchEndpoint;
import io.kamax.matrix.bridge.voip.remote.call.FreeswitchListener;
import io.kamax.matrix.bridge.voip.remote.call.FreeswitchManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RemoteManager {

    private final Logger log = LoggerFactory.getLogger(RemoteManager.class);

    private FreeswitchManager as;

    private Map<String, RemoteEndpoint> endpoints = new ConcurrentHashMap<>();
    private List<RemoteListener> listeners = new ArrayList<>();

    public RemoteManager() {
        as = new FreeswitchManager();
        as.addListener(new FreeswitchListener() {

            @Override
            public void onCallCreate(FreeswitchEndpoint endpoint, String origin, CallInviteEvent ev) {
                listeners.forEach(l -> l.onCallCreate(getEndpoint(ev.getCallId()), origin, ev));
            }

            @Override
            public void onCallDestroy(FreeswitchEndpoint endpoint, CallHangupEvent ev) {
                listeners.forEach(l -> l.onCallDestroy(getEndpoint(ev.getCallId()), ev));
            }

        });
    }

    public void addListener(RemoteListener listener) {
        listeners.add(listener);
    }

    public RemoteEndpoint getEndpoint(String callId) {
        log.info("Call {}: Creating endpoint", callId);
        return endpoints.computeIfAbsent(callId, cId -> new RemoteEndpoint(as.getEndpoint(cId)));
    }

}