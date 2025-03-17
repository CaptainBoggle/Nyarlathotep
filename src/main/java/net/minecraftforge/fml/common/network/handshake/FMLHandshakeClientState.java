/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.network.handshake;

import com.google.common.graph.Network;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerHandshakeMemory;
import net.minecraft.client.network.ServerPinger;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.status.INetHandlerStatusClient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.ExtendedServerListData;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.FMLHandshakeMessage.ServerHello;
import net.minecraftforge.fml.common.network.internal.FMLMessage;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import org.checkerframework.checker.units.qual.N;

/**
 * Packet handshake sequence manager- client side (responding to remote server)
 *
 * Flow:
 * 1. Wait for server hello. (START). Move to HELLO state.
 * 2. Receive Server Hello. Send customchannel registration. Send Client Hello. Send our modlist. Move to WAITINGFORSERVERDATA state.
 * 3. Receive server modlist. Send ack if acceptable, else send nack and exit error. Receive server IDs. Move to COMPLETE state. Send ack.
 *
 * @author cpw
 *
 */
enum FMLHandshakeClientState implements IHandshakeState<FMLHandshakeClientState>
{
    START
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            cons.accept(HELLO);
            NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
            dispatcher.clientListenForServerHandshake();
        }
    },
    HELLO
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            NetworkManager man = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get().manager;

            String rawAddress = man.getRemoteAddress().toString();

            // addressString is in the form hostname/ip:port
            // if the user is connecting via an ip, the hostname will be empty
            // we need to get the hostname if it is there, otherwise we will use the ip


            String serverIP = rawAddress.startsWith("/") ? rawAddress.substring(1, rawAddress.indexOf(":")) : rawAddress.substring(0, rawAddress.indexOf("/"));
            FMLHandshakeMessage.ModList epicModList = new FMLHandshakeMessage.ModList(Loader.instance().getActiveModList());

            FMLClientHandler client = FMLClientHandler.instance();

            Map<ServerData, ExtendedServerListData> serverListData = client.serverDataTag;

            // Yes, this falls apart if we have multiple servers with the same IP, but that's a problem for another day
            for (Map.Entry<ServerData, ExtendedServerListData> entry : serverListData.entrySet())
            {
                if (entry.getKey().serverIP.equals(serverIP))
                {
                    epicModList = new FMLHandshakeMessage.ModList(entry.getValue().modData);
                    FMLLog.log.info("Spoofing mod list!");
                    break;
                }
            }




            // resume normal code
            boolean isVanilla = msg == null;
            if (isVanilla)
            {
                cons.accept(DONE);
            }
            else
            {
                cons.accept(WAITINGSERVERDATA);
            }
            // write our custom packet registration, always
            ctx.writeAndFlush(FMLHandshakeMessage.makeCustomChannelRegistration(NetworkRegistry.INSTANCE.channelNamesFor(Side.CLIENT)));
            if (isVanilla)
            {
                NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
                dispatcher.abortClientHandshake("VANILLA");
                // VANILLA login
                return;
            }

            ServerHello serverHelloPacket = (FMLHandshakeMessage.ServerHello)msg;
            FMLLog.log.info("Server protocol version {}", Integer.toHexString(serverHelloPacket.protocolVersion()));
            if (serverHelloPacket.protocolVersion() > 1)
            {
                // Server sent us an extra dimension for the logging in player - stash it for retrieval later
                NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
                dispatcher.setOverrideDimension(serverHelloPacket.overrideDim());
            }
            ctx.writeAndFlush(new FMLHandshakeMessage.ClientHello()).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            //ctx.writeAndFlush(new FMLHandshakeMessage.ModList(Loader.instance().getActiveModList())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            // grab the servers mod list and send it back
            ctx.writeAndFlush(epicModList).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },

    WAITINGSERVERDATA
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            String modRejections = FMLNetworkHandler.checkModList((FMLHandshakeMessage.ModList) msg, Side.SERVER);
            if (modRejections != null)
            {
                cons.accept(ERROR);
                NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
                dispatcher.rejectHandshake(modRejections);
                return;
            }
            if (!ctx.channel().attr(NetworkDispatcher.IS_LOCAL).get())
            {
                cons.accept(WAITINGSERVERCOMPLETE);
            }
            else
            {
                cons.accept(PENDINGCOMPLETE);
            }
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

        }
    },
    WAITINGSERVERCOMPLETE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            FMLHandshakeMessage.RegistryData pkt = (FMLHandshakeMessage.RegistryData)msg;
            Map<ResourceLocation, ForgeRegistry.Snapshot> snap = ctx.channel().attr(NetworkDispatcher.FML_GAMEDATA_SNAPSHOT).get();
            if (snap == null)
            {
                snap = Maps.newHashMap();
                ctx.channel().attr(NetworkDispatcher.FML_GAMEDATA_SNAPSHOT).set(snap);
            }

            ForgeRegistry.Snapshot entry = new ForgeRegistry.Snapshot();
            entry.ids.putAll(pkt.getIdMap());
            entry.dummied.addAll(pkt.getDummied());
            entry.overrides.putAll(pkt.getOverrides());
            snap.put(pkt.getName(), entry);

            if (pkt.hasMore())
            {
                cons.accept(WAITINGSERVERCOMPLETE);
                FMLLog.log.debug("Received Mod Registry mapping for {}: {} IDs {} overrides {} dummied", pkt.getName(), entry.ids.size(), entry.overrides.size(), entry.dummied.size());
                return;
            }

            ctx.channel().attr(NetworkDispatcher.FML_GAMEDATA_SNAPSHOT).set(null);

            //Do the remapping on the Client's thread in case things are reset while the client is running. We stall the network thread until this is finished which can cause the IO thread to time out... Not sure if we can do anything about that.
            final Map<ResourceLocation, ForgeRegistry.Snapshot> snap_f = snap;
            Multimap<ResourceLocation, ResourceLocation> locallyMissing = Futures.getUnchecked(Minecraft.getMinecraft().addScheduledTask(() -> GameData.injectSnapshot(snap_f, false, false)));
//            if (!locallyMissing.isEmpty())
//            {
//                cons.accept(ERROR);
//                NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
//                dispatcher.rejectHandshake("Fatally missing registry entries");
//                FMLLog.log.fatal("Failed to connect to server: there are {} missing registry items", locallyMissing.size());
//                locallyMissing.asMap().forEach((key, value) ->  FMLLog.log.debug("Missing {} Entries: {}", key, value));
//                return;
//            }
            cons.accept(PENDINGCOMPLETE);
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },
    PENDINGCOMPLETE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            cons.accept(COMPLETE);
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },
    COMPLETE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            cons.accept(DONE);
            NetworkDispatcher dispatcher = ctx.channel().attr(NetworkDispatcher.FML_DISPATCHER).get();
            dispatcher.completeClientHandshake();
            FMLMessage.CompleteHandshake complete = new FMLMessage.CompleteHandshake(Side.CLIENT);
            ctx.fireChannelRead(complete);
            ctx.writeAndFlush(new FMLHandshakeMessage.HandshakeAck(ordinal())).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    },
    DONE
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
            if (msg instanceof FMLHandshakeMessage.HandshakeReset)
            {
                cons.accept(HELLO);
                //Run the revert on the client thread in case things are currently running to prevent race conditions while rebuilding the registries.
                Minecraft.getMinecraft().addScheduledTask(GameData::revertToFrozen);
            }
        }
    },
    ERROR
    {
        @Override
        public void accept(ChannelHandlerContext ctx, FMLHandshakeMessage msg, Consumer<? super FMLHandshakeClientState> cons)
        {
        }
    };
}
