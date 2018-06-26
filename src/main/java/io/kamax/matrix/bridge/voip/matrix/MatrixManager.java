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

package io.kamax.matrix.bridge.voip.matrix;

import com.google.gson.JsonObject;
import io.kamax.matrix.MatrixID;
import io.kamax.matrix.MatrixIdCodec;
import io.kamax.matrix._MatrixID;
import io.kamax.matrix._MatrixUserProfile;
import io.kamax.matrix.bridge.voip.*;
import io.kamax.matrix.bridge.voip.config.EntityTemplateConfig;
import io.kamax.matrix.bridge.voip.config.HomeserverConfig;
import io.kamax.matrix.client.MatrixClientContext;
import io.kamax.matrix.client._MatrixClient;
import io.kamax.matrix.client.as.MatrixApplicationServiceClient;
import io.kamax.matrix.client.as._MatrixApplicationServiceClient;
import io.kamax.matrix.event.*;
import io.kamax.matrix.hs.RoomMembership;
import io.kamax.matrix.hs._MatrixRoom;
import io.kamax.matrix.json.GsonUtil;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class MatrixManager {

    private final Logger log = LoggerFactory.getLogger(MatrixManager.class);

    private String domain;
    private List<Pattern> patterns;
    private _MatrixApplicationServiceClient as;
    private Map<String, MatrixBridgeUser> vMxUsers = new ConcurrentHashMap<>();
    private Map<String, MatrixEndpoint> endpoints = new ConcurrentHashMap<>();

    private List<MatrixListener> listeners = new ArrayList<>();

    public MatrixManager(HomeserverConfig hsCfg) {
        this.domain = hsCfg.getDomain();

        patterns = new ArrayList<>();
        for (EntityTemplateConfig entityTemplate : hsCfg.getUsers()) {
            patterns.add(Pattern.compile(entityTemplate.getTemplate().replace("%REMOTE_ID%", "(?<remoteId>.*)")));
        }
        if (patterns.size() < 1) {
            log.error("At least one user template must be configured");
            System.exit(1);
        }

        as = new MatrixApplicationServiceClient(new MatrixClientContext()
                .setDomain(hsCfg.getDomain())
                .setHsBaseUrl(hsCfg.getHost())
                .setToken(hsCfg.getAsToken())
                .setUserWithLocalpart(hsCfg.getLocalpart()));
    }

    private Optional<Matcher> findMatcherForUser(String localpart) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(localpart);
            if (m.matches()) {
                return Optional.of(m);
            }
        }

        return Optional.empty();
    }

    private Optional<Matcher> findMatcherForUser(_MatrixID mxId) {
        if (!mxId.getDomain().equals(domain)) {
            // Ignoring non-local user
            return Optional.empty();
        }

        return findMatcherForUser(mxId.getLocalPart());
    }

    public Optional<MatrixBridgeUser> findClientForUser(_MatrixID mxId) {
        return findMatcherForUser(mxId)
                .map(m -> vMxUsers.computeIfAbsent(m.group("remoteId"), id -> new MatrixBridgeUser(as.getUser(mxId.getLocalPart()), id)));
    }

    public HomeView forHome(String token) {

        return new HomeView() {

            private CircularFifoQueue<String> lastTransactions = new CircularFifoQueue<>(5);

            @Override
            public void queryUser(String user) throws UserNotFoundException {
                _MatrixID userId = MatrixID.asAcceptable(user);
                Matcher m = findMatcherForUser(userId).orElseThrow(() -> {
                    log.warn("Got queried about an unknown user {} : no pattern was found", userId.getId());
                    return new InvalidIdException(userId.getId());
                });

                String remoteId = MatrixIdCodec.decode(m.group("remoteId"));
                log.info("Creating virtual user for {} for remote ID {}", userId.getId(), remoteId);

                _MatrixClient client = as.createUser(userId.getLocalPart());
                client.setDisplayName(remoteId + " (Bridge)");
            }

            @Override
            public void queryRoom(String room) throws RoomNotFoundException {
                log.info("Room {} was requested, but we don't handle rooms", room);
                throw new RoomNotFoundException();
            }

            @Override
            public void process(Transaction transaction) {
                if (lastTransactions.contains(transaction.getId())) {
                    log.info("Transaction {} has already been processed, skipping", transaction.getId());
                    return;
                }

                for (_MatrixEvent event : transaction.getEvents()) {
                    log.info("Processing event {} of type {}", event.getId(), event.getType());
                    if (event instanceof _RoomMembershipEvent) {
                        pushMembershipEvent((_RoomMembershipEvent) event);
                    } else if (event instanceof _RoomMessageEvent) {
                        pushMessageEvent((_RoomMessageEvent) event);
                    } else if (event instanceof _RoomEvent) {
                        _RoomEvent rEvent = (_RoomEvent) event;
                        if (rEvent.getType().startsWith("m.call.")) {
                            pushCallEvent(rEvent);
                        }
                    } else {
                        log.info("Unknown event type {} from {}", event.getType(), event.getSender());
                    }
                }

                lastTransactions.add(transaction.getId());
            }

            private void pushMembershipEvent(_RoomMembershipEvent ev) {
                log.info("Room {}: Membership {} for {}", ev.getRoomId(), ev.getMembership(), ev.getInvitee());

                if (as.getUser().orElseGet(() -> as.getWhoAmI()).equals(ev.getInvitee())) {
                    log.info("Event for global AS user");
                    if (RoomMembership.Invite.is(ev.getMembership()) || RoomMembership.Join.is(ev.getMembership())) {
                        log.info("Leaving room: invite or join not allowed");
                        as.getRoom(ev.getRoomId()).tryLeave().ifPresent(err -> log.warn("Unable to leave: {}", err));
                    }
                } else {
                    findClientForUser(ev.getInvitee()).ifPresent(user -> {
                        log.info("Event for virtual user");
                        _MatrixRoom room = user.getClient().getRoom(ev.getRoomId());

                        if (RoomMembership.Invite.is(ev.getMembership())) {
                            log.info("Joining room");
                            room.tryJoin().ifPresent(errJoin -> {
                                log.info("Rejecting invite, unable to join room: {}", errJoin);
                                room.tryLeave().ifPresent(errLeave -> log.warn("Unable to leave: {}", errLeave));
                            });
                        }

                        if (RoomMembership.Join.is(ev.getMembership())) {
                            log.info("Joined room");
                        }

                        if (RoomMembership.Leave.is(ev.getMembership()) || RoomMembership.Ban.is(ev.getMembership())) {
                            log.info("Left room");
                            // FIXME trigger end of endpoints via event
                        }
                    });
                }
            }

            private void pushMessageEvent(_RoomMessageEvent ev) {
                log.info("Ignoring message event {} in room {} with body type {}", ev.getId(), ev.getRoomId(), ev.getBodyType());
            }

            private void pushCallEvent(_RoomEvent ev) {
                if (findMatcherForUser(ev.getSender()).isPresent()) {
                    log.info("Event about ourselves, skipping");
                    return;
                }

                log.info("Got call event", ev.getId());
                JsonObject content = EventKey.Content.findObj(ev.getJson()).orElseGet(JsonObject::new);
                CallEvent call = GsonUtil.get().fromJson(content, CallEvent.class);
                if (!call.isValid()) {
                    log.warn("Ignoring call event: invalid");
                    return;
                }

                if (!call.getVersion().equals(0L)) {
                    log.warn("Ignoring call event: unknown version: {}", call.getVersion());
                    return;
                }

                List<MatrixBridgeUser> vUsers = as.getRoom(ev.getRoomId()).getJoinedUsers().stream()
                        .filter(p -> findMatcherForUser(p.getId()).isPresent())
                        .map(_MatrixUserProfile::getId)
                        .flatMap(id -> Stream.of(findClientForUser(id)))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
                if (vUsers.size() > 1) {
                    log.info("Ignoring call event: room {} is not a 1:1 chat", ev.getRoomId());
                    return;
                }

                MatrixBridgeUser vUser = vUsers.get(0);
                if ("m.call.invite".equals(ev.getType())) {
                    log.info("Call {}: invite {}", call.getCallId(), vUser.getRemoteId());
                    CallInviteEvent data = GsonUtil.get().fromJson(content, CallInviteEvent.class);
                    if (!data.isValid()) {
                        log.warn("Call {}: Ignoring: Invalid", call.getCallId());
                        return;
                    }

                    long age = GsonUtil.findLong(ev.getJson(), "age").orElse(0L);
                    Instant ts = Instant.now().minus(age, ChronoUnit.MILLIS);
                    Instant expiredAt = ts.plus(data.getLifetime(), ChronoUnit.MILLIS);
                    log.info("Call {}: From {} expiring in {} ({})", call.getCallId(), ts, data.getLifetime(), expiredAt);
                    if (expiredAt.isBefore(Instant.now())) {
                        log.info("Call {}: Expired", call.getCallId());
                        return;
                    }

                    log.info(
                            "Call {}: Type {} with SDP:\n{}",
                            call.getCallId(),
                            data.getOffer().getType(),
                            data.getOffer().getSdp()
                    );

                    MatrixEndpoint mxCall = new MatrixEndpoint(vUser, ev.getRoomId(), call.getCallId());
                    endpoints.put(data.getCallId(), mxCall);
                    listeners.forEach(l -> l.onCallCreated(mxCall, vUser.getRemoteId(), data));
                    mxCall.inject(data);

                }

                if ("m.call.candidates".equals(ev.getType())) {
                    log.info("Call {}: candidates", call.getCallId());
                    CallCandidatesEvent data = GsonUtil.get().fromJson(content, CallCandidatesEvent.class);

                    MatrixEndpoint mxCall = endpoints.get(call.getCallId());
                    if (Objects.isNull(mxCall)) {
                        log.warn("Call {}: Unknown, ignoring", call.getCallId());
                        return;
                    }

                    mxCall.inject(data);
                }

                if ("m.call.answer".equals(ev.getType())) {
                    log.info("Call {}: answer", call.getCallId());
                    CallAnswerEvent data = GsonUtil.get().fromJson(content, CallAnswerEvent.class);
                    log.info("Data: {}", data.getAnswer());

                    MatrixEndpoint mxCall = endpoints.get(call.getCallId());
                    if (Objects.isNull(mxCall)) {
                        log.warn("Unknown call, ignoring");
                        return;
                    }

                    mxCall.inject(data);
                }

                if ("m.call.hangup".equals(ev.getType())) {
                    log.info("Call {}: hangup", call.getCallId());
                    CallHangupEvent data = GsonUtil.get().fromJson(content, CallHangupEvent.class);

                    MatrixEndpoint mxCall = endpoints.remove(call.getCallId());
                    if (Objects.isNull(mxCall)) {
                        log.warn("Unknown call, ignoring");
                        return;
                    }

                    mxCall.inject(data);
                    listeners.forEach(l -> l.onCallDestroyed(mxCall, data));
                }
            }

        };
    }

    public IdentityView forIdentity() {
        return new VoipIdentityView();
    }

    public void addListener(MatrixListener listener) {
        listeners.add(listener);
    }

    public MatrixEndpoint getEndpoint(String userId, String roomId, String callId) {
        MatrixBridgeUser user = findClientForUser(MatrixID.asAcceptable("_voip_" + userId, domain)).orElseThrow(IllegalArgumentException::new);
        MatrixEndpoint endpoint = new MatrixEndpoint(user, roomId, callId);
        endpoint.addListener(new CallListener() { // FIXME do better
            @Override
            public void onInvite(String from, CallInviteEvent ev) {

            }

            @Override
            public void onSdp(CallSdpEvent ev) {

            }

            @Override
            public void onCandidates(CallCandidatesEvent ev) {

            }

            @Override
            public void onAnswer(CallAnswerEvent ev) {

            }

            @Override
            public void onHangup(CallHangupEvent ev) {

            }

            @Override
            public void onClose() {
                log.info("Removing endpoint for Call {}: closed", callId);
                endpoints.remove(callId);
            }
        });
        endpoints.put(callId, endpoint);
        return endpoint;
    }

}
