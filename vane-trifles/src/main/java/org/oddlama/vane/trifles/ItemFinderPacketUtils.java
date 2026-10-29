package org.oddlama.vane.trifles;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.oddlama.vane.util.Scheduling;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

public class ItemFinderPacketUtils {
    private final static long GLOW_DURATION = 20L * 8L; // 8 Seconds
    private final static float CONTAINER_DEFAULT_SCALE = 0.95f;
    private final static float CONTAINER_DEFAULT_TRANSLATION = (1 - CONTAINER_DEFAULT_SCALE) * 0.5f;
    private final static float CHEST_DEFAULT_SCALE = 0.85f;

    static void indicate_entity_match(@NotNull Trifles module, @NotNull Player player, @NotNull Entity entity) {
        // Get base entity data
        var base_entity_data = SpigotConversionUtil.getEntityMetadata(entity);

        // Generate metadata with glowing turned on
        var glowing_entity_data = new ArrayList<>(base_entity_data);
        var glow_data = new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x40);
        glowing_entity_data.add(glow_data);

        // Create packet and set glowing metadata
        var glowing_packet = new WrapperPlayServerEntityMetadata(
                entity.getEntityId(),
                glowing_entity_data
        );

        // Send packet
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, glowing_packet);

        // Schedule repeating packet b/c if the player looks away from entities, they will stop glowing.
        // Runs on the player's scheduler, which drops it when the player leaves.
        var repeating_packet_task = Scheduling.entity_timer(
            module,
            player,
            task -> PacketEvents.getAPI().getPlayerManager().sendPacket(player, glowing_packet),
            null,
            1,
            1
        );

        // Create non-glowing packet
        var non_glowing_entity_data = new ArrayList<>(base_entity_data);
        WrapperPlayServerEntityMetadata non_glowing_packet = new WrapperPlayServerEntityMetadata(
                entity.getEntityId(),
                non_glowing_entity_data
        );

        // Run task later to send packet to stop glowing
        schedule_reset(module, player, non_glowing_packet, repeating_packet_task);
    }

    static void indicate_container_match(@NotNull Trifles module, @NotNull Player player, @NotNull Container container) {
        int display_id = SpigotReflectionUtil.generateEntityId();
        // Check for Shulkers so we can handle them differently
        var entity_type = (container instanceof ShulkerBox) ? EntityTypes.SHULKER : EntityTypes.BLOCK_DISPLAY;
        var display_packet = new WrapperPlayServerSpawnEntity(
                display_id,
                UUID.randomUUID(),
                entity_type,
                SpigotConversionUtil.fromBukkitLocation(container.getLocation()),
                container.getLocation().getYaw(),
                0,
                null
        );

        // Metadata
        var display_metadata = new ArrayList<EntityData<?>>();
        display_metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) (0x20 | 0x40)));
        if (entity_type == EntityTypes.BLOCK_DISPLAY) {
            display_metadata.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, new Vector3f(CONTAINER_DEFAULT_TRANSLATION, CONTAINER_DEFAULT_TRANSLATION, CONTAINER_DEFAULT_TRANSLATION)));
            display_metadata.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, new Vector3f(CONTAINER_DEFAULT_SCALE, CONTAINER_DEFAULT_SCALE, CONTAINER_DEFAULT_SCALE)));
            display_metadata.add(new EntityData<>(23, EntityDataTypes.BLOCK_STATE, SpigotConversionUtil.fromBukkitBlockData(container.getBlockData()).getGlobalId()));
        }
        var display_metadata_packet = new WrapperPlayServerEntityMetadata(
                display_id,
                display_metadata
        );

        // Send packets
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, display_packet);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, display_metadata_packet);

        // Shulker specific scale attribute packet
        if (entity_type == EntityTypes.SHULKER) {
            var scale_attribute = Attributes.SCALE;

            // Create scale attribute modifier
            var attribute_modifier = new WrapperPlayServerUpdateAttributes.PropertyModifier(
                    UUID.randomUUID(),
                    CONTAINER_DEFAULT_SCALE - 1,
                    WrapperPlayServerUpdateAttributes.PropertyModifier.Operation.ADDITION
            );
            // Create scale attribute base property
            var attribute_property = new WrapperPlayServerUpdateAttributes.Property(
                    scale_attribute,
                    1.0,
                    Collections.singletonList(attribute_modifier)
            );
            // Create update attribute packet
            var attribute_packet = new WrapperPlayServerUpdateAttributes(
                    display_id,
                    Collections.singletonList(attribute_property)
            );

            // Send packet
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, attribute_packet);
        }

        // Construct destroy entity packet
        var destroy_entity_packet = new WrapperPlayServerDestroyEntities(display_id);

        // Run task later to remove entity
        schedule_reset(module, player, destroy_entity_packet, null);
    }

    // Special function for chests
    static void indicate_chest_match(@NotNull Trifles module, @NotNull Player player, @NotNull Container container) {
        int display_id = SpigotReflectionUtil.generateEntityId();
        var display_packet = new WrapperPlayServerSpawnEntity(
                display_id,
                UUID.randomUUID(),
                EntityTypes.SHULKER,
                SpigotConversionUtil.fromBukkitLocation(container.getLocation()),
                container.getLocation().getYaw(),
                0,
                null
        );

        // Metadata
        var display_metadata = new ArrayList<EntityData<?>>();
        display_metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) (0x20 | 0x40)));
        var display_metadata_packet = new WrapperPlayServerEntityMetadata(
                display_id,
                display_metadata
        );

        // Send packets
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, display_packet);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, display_metadata_packet);

        // Shulker specific scale attribute packet
        var scale_attribute = Attributes.SCALE;

        // Create scale attribute modifier
        var attribute_modifier = new WrapperPlayServerUpdateAttributes.PropertyModifier(
                UUID.randomUUID(),
                CHEST_DEFAULT_SCALE - 1,
                WrapperPlayServerUpdateAttributes.PropertyModifier.Operation.ADDITION
        );
        // Create scale attribute base property
        var attribute_property = new WrapperPlayServerUpdateAttributes.Property(
                scale_attribute,
                1.0,
                Collections.singletonList(attribute_modifier)
        );
        // Create update attribute packet
        var attribute_packet = new WrapperPlayServerUpdateAttributes(
                display_id,
                Collections.singletonList(attribute_property)
        );

        // Send packet
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, attribute_packet);

        // Construct destroy entity packet
        var destroy_entity_packet = new WrapperPlayServerDestroyEntities(display_id);

        // Run task later to remove entity
        schedule_reset(module, player, destroy_entity_packet, null);
    }

    // Sends the reset packet after the glow duration, on the player's scheduler. If the player
    // leaves first the scheduler drops the task (and the repeating one), and their client
    // forgets the fake entities anyway.
    private static void schedule_reset(
        @NotNull Trifles module,
        @NotNull Player player,
        @NotNull PacketWrapper<?> packet,
        @Nullable ScheduledTask optional_repeating_packet_task
    ) {
        Scheduling.entity(
            module,
            player,
            () -> {
                if (optional_repeating_packet_task != null) {
                    optional_repeating_packet_task.cancel();
                }

                PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            },
            () -> {
                if (optional_repeating_packet_task != null) {
                    optional_repeating_packet_task.cancel();
                }
            },
            GLOW_DURATION
        );
    }
}
