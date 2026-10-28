package me.matl114.addon.containerscanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.matl114.accessors.access.ChunkAccess;
import me.matl114.events.Event;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.inv.ChestHistory;
import me.matl114.hacks.modules.render.IRender2DColoredModule;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.utils.CommonUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import me.matl114.utils.world.ContainerPosition;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;

public final class ContainerScannerModule extends IRender2DColoredModule {
    public static ContainerScannerModule INSTANCE;

    private static final String ROOT = "container-scanner";

    public final IntRef radius = builder(hud.add("radius"), IntRef.TYPE)
            .defaultValue(32)
            .validator(Configs.intRange(8, 128))
            .build();

    public final IntRef refreshInterval = builder(hud.add("refresh-interval"), IntRef.TYPE)
            .defaultValue(20)
            .validator(Configs.intRange(1, 200))
            .build();

    public final IntRef maxLines = builder(hud.add("max-lines"), IntRef.TYPE)
            .defaultValue(10)
            .validator(Configs.intRange(1, 50))
            .build();

    public final FlagRef showContent =
            flagBuilder(hud.add("show-content")).defaultValue(true).build();

    public final FlagRef showCoordinates =
            flagBuilder(hud.add("show-coordinates")).defaultValue(true).build();

    public final FlagRef esp = flagBuilder(hud.add("esp")).defaultValue(true).build();

    public final FlagRef tracer =
            flagBuilder(hud.add("tracer")).defaultValue(false).build();

    public final NBTRef<WrapColor> espColor = builder(hud.add("esp-color"), WrapColor.class)
            .defaultValue(new WrapColor("#00FFAA"))
            .build();

    public final NBTRef<EntrySet<BlockEntityType<?>>> typeFilter = builder(
                    hud.add("types"), EntrySet.<BlockEntityType<?>>parameter())
            .defaultValue(new EntrySet<>(
                    new Regex("^(.*chest|barrel|.*box|hopper|furnace|smoker|blast_furnace|dispenser|dropper|crafter)$"),
                    Registries.BLOCK_ENTITY_TYPE))
            .build();

    private final RenderCollector<Box> boxCollector =
            RenderCollectors.createBoxCollector(true, false, false);
    private final RenderCollector<Vec3d> tracerCollector = RenderCollectors.createTracerCollector();

    private List<ContainerInfo> results = List.of();
    private int tickCounter;

    public ContainerScannerModule() {
        super("ContainerScanner");
        INSTANCE = this;
    }

    @Override
    protected ModulePath createRoot() {
        return makePath(ContainerScannerAddon.CONFIG, ROOT);
    }

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        tickCounter = 0;
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        results = List.of();
        clearRender();
    }

    @Override
    public void onUpdate(Event<Void> event) {
        if (checkNull() || !enable.get()) {
            if (!results.isEmpty()) {
                results = List.of();
                clearRender();
            }
            return;
        }
        if (tickCounter++ % Math.max(1, refreshInterval.get()) != 0) {
            return;
        }
        scan();
    }

    @Override
    public void render2D(VDrawContext vdraw, float partialTicks) {
        if (checkNull()) {
            return;
        }
        List<ContainerInfo> list = results;
        if (list.isEmpty()) {
            drawText(vdraw, Text.literal("容器扫描: 未发现容器"));
            return;
        }
        drawText(vdraw, Text.literal("容器扫描 (" + list.size() + ")"));
        int max = Math.max(1, maxLines.get());
        int shown = 0;
        for (ContainerInfo info : list) {
            if (shown >= max) {
                drawText(vdraw, Text.literal("... +" + (list.size() - shown) + " 个"));
                break;
            }
            MutableText line = Text.empty();
            line.append(Text.literal(String.format(Locale.ROOT, "[%.1fm] ", info.distance())));
            line.append(info.displayName());
            if (showCoordinates.get()) {
                line.append(Text.literal(" " + info.pos().toShortString()));
            }
            if (showContent.get()) {
                if (info.contentSummary() != null) {
                    line.append(Text.literal(" | "));
                    line.append(info.contentSummary());
                } else {
                    line.append(Text.literal(" | 未打开"));
                }
            }
            drawText(vdraw, line);
            shown++;
        }
    }

    private void scan() {
        if (mc.player == null || mc.world == null) {
            return;
        }
        double radiusValue = radius.get();
        double radiusSq = radiusValue * radiusValue;
        Vec3d playerPos = mc.player.getPos();
        int chunkRadius = Math.max(1, (int) Math.ceil(radiusValue / 16.0D));
        ChunkPos center = mc.player.getChunkPos();
        Map<ContainerPosition, ContainerInfo> unique = new LinkedHashMap<>();
        for (Chunk chunk : CommonUtils.chunks(false)) {
            ChunkPos chunkPos = chunk.getPos();
            if (Math.abs(chunkPos.x - center.x) > chunkRadius || Math.abs(chunkPos.z - center.z) > chunkRadius) {
                continue;
            }
            for (Map.Entry<BlockPos, BlockEntity> entry : ChunkAccess.of(chunk).blockEntityEntries()) {
                BlockPos pos = entry.getKey();
                BlockEntity blockEntity = entry.getValue();
                if (!typeFilter.get().test(blockEntity.getType())) {
                    continue;
                }
                if (pos.getSquaredDistance(playerPos.x, playerPos.y, playerPos.z) > radiusSq) {
                    continue;
                }
                ContainerPosition containerPosition = resolveContainerPosition(pos, blockEntity);
                ContainerInfo info = createInfo(pos, blockEntity, containerPosition, playerPos);
                ContainerInfo old = unique.get(containerPosition);
                if (old == null || info.distance() < old.distance()) {
                    unique.put(containerPosition, info);
                }
            }
        }
        List<ContainerInfo> found = new ArrayList<>(unique.values());
        found.sort(Comparator.comparingDouble(ContainerInfo::distance));
        results = List.copyOf(found);
        updateRender();
    }

    private ContainerPosition resolveContainerPosition(BlockPos pos, BlockEntity blockEntity) {
        var state = blockEntity.getCachedState();
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            return ContainerPosition.resolve(mc.world, pos);
        }
        return ContainerPosition.ofSingle(mc.world, pos);
    }

    private ContainerInfo createInfo(
            BlockPos pos, BlockEntity blockEntity, ContainerPosition containerPosition, Vec3d playerPos) {
        double distance = Math.sqrt(pos.getSquaredDistance(playerPos.x, playerPos.y, playerPos.z));
        Text displayName = blockEntity.getCachedState().getBlock().getName();
        ChestHistory history = ChestHistory.INSTANCE;
        ChestHistory.Entry cache = null;
        if (history != null) {
            if (blockEntity instanceof EnderChestBlockEntity) {
                cache = history.getTrackedEnderChestEntry();
            } else {
                cache = history.getEntry(containerPosition);
            }
        }
        Text summary = cache == null ? null : buildSummary(cache);
        return new ContainerInfo(
                pos, containerPosition, displayName, distance, summary, containerPosition.getBoundingBox());
    }

    private Text buildSummary(ChestHistory.Entry entry) {
        Inventory inventory = entry.getInventory();
        if (inventory == null || inventory.size() == 0) {
            return Text.literal("空");
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        Map<Item, ItemStack> samples = new LinkedHashMap<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            counts.merge(item, stack.getCount(), Integer::sum);
            samples.putIfAbsent(item, stack);
        }
        if (counts.isEmpty()) {
            return Text.literal("空");
        }
        MutableText text = Text.empty();
        int shown = 0;
        for (Map.Entry<Item, Integer> entryCount : counts.entrySet()) {
            if (shown >= 3) {
                text.append(Text.literal(" +" + (counts.size() - shown) + "种"));
                break;
            }
            if (shown > 0) {
                text.append(Text.literal(", "));
            }
            text.append(samples.get(entryCount.getKey()).getName());
            text.append(Text.literal(" x" + entryCount.getValue()));
            shown++;
        }
        return text;
    }

    private void updateRender() {
        clearRender();
        int color = espColor.get().withAlpha(180);
        for (ContainerInfo info : results) {
            boxCollector.submit(info.box(), color);
            tracerCollector.submit(info.containerPosition().getCenterPosition(), color);
        }
    }

    private void clearRender() {
        boxCollector.clear();
        tracerCollector.clear();
    }

    private void onRender3D(Event<Render3D> event) {
        if (!enable.get() || checkNull()) {
            return;
        }
        boolean drawEsp = esp.get();
        boolean drawTracer = tracer.get();
        if (!drawEsp && !drawTracer) {
            return;
        }
        MatrixStack stack = event.context().stack();
        boolean started = RenderUtils.startDrawVirtual(stack);
        try {
            if (drawEsp) {
                boxCollector.render3D(stack, event.context().partialTicks());
            }
            if (drawTracer) {
                tracerCollector.render3D(stack, event.context().partialTicks());
            }
        } finally {
            if (started) {
                RenderUtils.stopDrawVirtual(stack);
            }
        }
    }

    public record ContainerInfo(
            BlockPos pos,
            ContainerPosition containerPosition,
            Text displayName,
            double distance,
            Text contentSummary,
            Box box) {}
}
