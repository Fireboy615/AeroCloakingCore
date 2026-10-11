package net.fireboy.aerocloakingcore.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.fireboy.aerocloakingcore.block.CloakingCoreBlock;
import net.fireboy.aerocloakingcore.block.CloakingCoreChamberBlock;
import net.fireboy.aerocloakingcore.block.ModBlocks;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class AeroCloakingCorePonderScenes {

    private AeroCloakingCorePonderScenes() {
    }

    public static void cloakingCore(
            SceneBuilder builder,
            SceneBuildingUtil util
    ) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);

        scene.title(
                "cloaking_core",
                "Powering and Operating a Cloaking Core"
        );
        scene.configureBasePlate(0, 0, 5);

        BlockPos corePos = util.grid().at(3, 1, 2);
        BlockPos chamberPos = util.grid().at(4, 1, 2);

        // A few hull blocks make the later field demonstration readable:
        // the *ship and core* fade out together, not merely the core rotor.
        var cloakSelection = util.select().fromTo(3, 1, 1, 4, 1, 3);

        BlockState driveState = ModBlocks.CLOAKING_CORE.get()
                .defaultBlockState()
                .setValue(CloakingCoreBlock.FACING, Direction.EAST)
                .setValue(CloakingCoreBlock.CHAMBER, false);

        BlockState chamberState = ModBlocks.CLOAKING_CORE_CHAMBER.get()
                .defaultBlockState()
                .setValue(CloakingCoreChamberBlock.FACING, Direction.EAST);

        scene.world().setBlock(corePos, driveState, false);
        scene.world().setBlock(chamberPos, chamberState, false);
        scene.world().setBlocks(
                util.select().fromTo(3, 1, 1, 4, 1, 1),
                Blocks.STONE_BRICKS.defaultBlockState(),
                false
        );
        scene.world().setBlocks(
                util.select().fromTo(3, 1, 3, 4, 1, 3),
                Blocks.STONE_BRICKS.defaultBlockState(),
                false
        );

        scene.world().showSection(util.select().layer(0), Direction.UP);
        scene.idle(5);
        scene.world().showSection(
                util.select().fromTo(1, 1, 2, 2, 1, 2),
                Direction.EAST
        );
        scene.idle(10);

        scene.world().showSection(
                cloakSelection,
                Direction.DOWN
        );
        scene.idle(15);

        scene.overlay().showText(70)
                .text("Rotational power enters through the shaft at the rear of the Core.")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(corePos, Direction.WEST));
        scene.idle(80);

        scene.addKeyframe();

        scene.world().setKineticSpeed(
                util.select().fromTo(1, 1, 2, 3, 1, 2),
                32
        );
        scene.effects().rotationSpeedIndicator(corePos);

        scene.overlay().showText(70)
                .text("A Cloaking Core needs at least 32 RPM before it can operate.")
                .colored(net.createmod.ponder.api.PonderPalette.GREEN)
                .placeNearTarget()
                .pointAt(util.vector().topOf(corePos));
        scene.idle(80);

        scene.addKeyframe();

        scene.world().setKineticSpeed(
                util.select().fromTo(1, 1, 2, 3, 1, 2),
                128
        );
        scene.effects().rotationSpeedIndicator(corePos);

        scene.overlay().showText(80)
                .text("Capacity scales at 2 ship blocks per RPM, up to 256 blocks per Core at 128 RPM.")
                .placeNearTarget()
                .pointAt(util.vector().topOf(chamberPos));
        scene.idle(90);
        scene.addKeyframe();

        scene.overlay().showControls(
                        util.vector().blockSurface(corePos, Direction.NORTH),
                        Pointing.DOWN,
                        45
                )
                .rightClick();

        scene.overlay().showText(80)
                .text("Right-click the Core to configure cloak strength, render mode, and Redstone Link control.")
                .placeNearTarget()
                .pointAt(util.vector().blockSurface(corePos, Direction.NORTH));
        scene.idle(90);

        scene.addKeyframe();

        // Ponder has its own tiny preview world, not a Sable ship.
        // Hide/show with NO direction, so the machine and small hull FADE
        // out in place instead of flying away like a moving contraption.
        scene.overlay().showText(90)
                .text("Once the field is active, the ship and its Cloaking Core fade from view.")
                .colored(net.createmod.ponder.api.PonderPalette.GREEN)
                .placeNearTarget()
                .pointAt(util.vector().centerOf(corePos));
        scene.idle(20);
        scene.world().hideSection(cloakSelection, null);
        scene.idle(80);

        scene.addKeyframe();

        scene.overlay().showText(80)
                .text("When the cloak is disabled or power is lost, the ship becomes visible again.")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(chamberPos));
        scene.idle(20);
        scene.world().showSection(cloakSelection, null);
        scene.idle(70);

        scene.addKeyframe();

        scene.overlay().showText(90)
                .text("Create Goggles show live cloak status, ship load, capacity, speed, and field efficiency.")
                .placeNearTarget()
                .pointAt(util.vector().topOf(corePos));
        scene.idle(100);

        scene.overlay().showText(80)
                .text("Larger ships and stronger cloaking increase stress. Multiple Cores can share the load.")
                .placeNearTarget()
                .pointAt(util.vector().centerOf(chamberPos));
        scene.idle(90);

        scene.markAsFinished();
    }
}
