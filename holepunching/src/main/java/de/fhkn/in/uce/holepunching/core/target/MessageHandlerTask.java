/*
 * Copyright (c) 2012 Alexander Diener,
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.fhkn.in.uce.holepunching.core.target;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.fhkn.in.uce.holepunching.core.CancelableTask;
import de.fhkn.in.uce.holepunching.core.ConnectionListener;
import de.fhkn.in.uce.holepunching.core.HolePuncher;
import de.fhkn.in.uce.holepunching.core.HolePunchingUtil;
import de.fhkn.in.uce.holepunching.message.HolePunchingMethod;
import de.fhkn.in.uce.stun.attribute.MappedAddress;
import de.fhkn.in.uce.stun.attribute.Token;
import de.fhkn.in.uce.stun.message.Message;
import de.fhkn.in.uce.stun.message.MessageReader;

/**
 * Task that is waiting for forward endpoints messages and keep-live messages.
 * If a forward endpoints message arrives it starts the hole punching process.
 * If a keep-live messages it does nothing.
 * 
 * @author dmaier, Alexander Diener (aldiener@htwg-konstanz.de)
 * 
 */
public final class MessageHandlerTask implements CancelableTask {
    private final static Logger logger = LoggerFactory.getLogger(MessageHandlerTask.class);
    private final Socket socketToMediator;
    private final ConnectionListener connectionListener;
    private final HolePuncher hp;
    private boolean cancelled = false;
    private final HolePunchingUtil hpUtil;

    /**
     * Creates a new {@link MessageHandlerTask}.
     * 
     * @param socketToMediator
     *            connection to the mediator.
     * @param socketQueue
     *            queue to put established and authenticated hole punching
     *            connections.
     */
    public MessageHandlerTask(final Socket socketToMediator, final BlockingQueue<Socket> socketQueue, final int port) {
        this.hpUtil = HolePunchingUtil.getInstance();
        this.socketToMediator = socketToMediator;
        final SocketAddress localSocketAddress = new InetSocketAddress(port);
        this.connectionListener = new ConnectionListener(socketToMediator.getLocalAddress(),
                socketToMediator.getLocalPort());
        this.hp = new HolePuncher(this.connectionListener, localSocketAddress, socketQueue);
    }

    /**
     * Waits in a loop for forward endpoints messages and keep-live messages
     * until the task gets canceled. If a forward endpoints message arrives it
     * tries to establish a hole punching connection. To do this it uses for
     * each arriving forward endpoints messages the same {@link HolePuncher}
     * object. If a keep-live messages it does nothing.
     */
    @Override
    public void run() {
        final MessageReader messageReader = this.hpUtil.getCustomHolePunchingMessageReader();
        while (!this.cancelled) {
            try {
                final Message receivedMessage = messageReader.readSTUNMessage(this.socketToMediator.getInputStream());
                if (this.isForwardedEndpointsMessage(receivedMessage)) {
                    logger.info("New ForwardedEndpointsMessage, starting hole punching"); //$NON-NLS-1$
                    final TargetConnectionAuthenticator authentification = new TargetConnectionAuthenticator(
                            receivedMessage.getAttribute(Token.class).getToken());
                    final List<MappedAddress> addresses = receivedMessage.getAttributes(MappedAddress.class);
                    this.startHolePunching(addresses, authentification);
                }
            } catch (final IOException e) {
                logger.error("Exception while reading incoming message", e); //$NON-NLS-1$
            }
        }
    }

    private boolean isForwardedEndpointsMessage(final Message toCheck) {
        return toCheck.isMethod(HolePunchingMethod.FORWARDED_ENDPOINTS) && toCheck.hasAttribute(MappedAddress.class)
                && toCheck.hasAttribute(Token.class);
    }

    private void startHolePunching(final List<MappedAddress> endpoints,
            final TargetConnectionAuthenticator authentification) {
        // hole puncher expects exactly two endpoints, if more
        // endpoints are provided use the first two
        final InetSocketAddress endpointOne = endpoints.get(0).getEndpoint();
        final InetSocketAddress endpointTwo = endpoints.get(1).getEndpoint();
        this.hp.establishHolePunchingConnection(endpointOne.getAddress(), endpointOne.getPort(),
                endpointTwo.getAddress(), endpointTwo.getPort(), authentification);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
        this.connectionListener.shutdown();
        this.hp.shutdownNow();
    }
}
