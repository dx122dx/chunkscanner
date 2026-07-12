package com.billy65536.chunkscanner.mixin;

import com.billy65536.chunkscanner.components.analyzer.QShopChatListener;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截系统聊天数据包，将内容转发给 {@link QShopChatListener}。
 *
 * <p>{@link net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents}
 * 仅捕获 {@link net.minecraft.network.packet.s2c.play.GameMessageS2CPacket}
 * （玩家聊天 + 游戏覆盖层），不处理 {@link ChatMessageS2CPacket}
 * （服务端插件通过 Bukkit/Spigot 的 player.sendMessage() 发送的系统消息）。
 * QuickShop 正是通过系统消息发送物品信息，因此需要此 Mixin 进行拦截。</p>
 */
@Mixin(ClientPlayNetworkHandler.class)
public class SystemChatMixin {

    @Inject(method = "onChatMessage", at = @At("HEAD"))
    private void chunkscanner$onSystemChatMessage(ChatMessageS2CPacket packet, CallbackInfo ci) {
        Text text = packet.unsignedContent();
        if (text == null && packet.body() != null) {
            text = Text.literal(packet.body().content());
        }
        if (text == null) return;
        QShopChatListener.onSystemMessage(text);
    }
}
