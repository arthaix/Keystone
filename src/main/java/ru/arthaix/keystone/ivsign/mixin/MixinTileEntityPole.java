package ru.arthaix.keystone.ivsign.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.blocks.components.ABlockBase;
import minecrafttransportsimulator.blocks.tileentities.components.ATileEntityPole_Component;
import minecrafttransportsimulator.blocks.tileentities.instances.TileEntityPole;
import minecrafttransportsimulator.items.instances.ItemPoleComponent;
import minecrafttransportsimulator.jsondefs.JSONPoleComponent;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import ru.arthaix.keystone.ivsign.SignAngle;

/** Pole components face the player at any angle (see SignAngle). */
@Mixin(value = TileEntityPole.class, remap = false)
public abstract class MixinTileEntityPole {
    /** The side of the pole a click acts on follows the snapped angle, so placing and removing agree with it. */
    @Redirect(method = "interact", at = @At(value = "INVOKE",
            target = "Lminecrafttransportsimulator/blocks/components/ABlockBase$Axis;getFromRotation(DZ)Lminecrafttransportsimulator/blocks/components/ABlockBase$Axis;"))
    private ABlockBase.Axis keystone$side(double playerYaw, boolean diagonals) {
        return ABlockBase.Axis.getFromRotation(SignAngle.viewYaw(SignAngle.fromPlayerYaw(playerYaw)), diagonals);
    }

    /** A newly placed component remembers the angle the player looked at. */
    @Redirect(method = "interact", at = @At(value = "INVOKE",
            target = "Lminecrafttransportsimulator/items/instances/ItemPoleComponent$PoleComponentType;createComponent(Lminecrafttransportsimulator/blocks/tileentities/instances/TileEntityPole;Lminecrafttransportsimulator/mcinterface/IWrapperPlayer;Lminecrafttransportsimulator/blocks/components/ABlockBase$Axis;Lminecrafttransportsimulator/mcinterface/IWrapperNBT;)Lminecrafttransportsimulator/blocks/tileentities/components/ATileEntityPole_Component;"))
    private ATileEntityPole_Component keystone$place(TileEntityPole pole, IWrapperPlayer player, ABlockBase.Axis axis, IWrapperNBT data) {
        List<String> names = new ArrayList<String>(data.getStrings("variables"));
        if (!names.contains(SignAngle.VARIABLE)) {
            names.add(SignAngle.VARIABLE);
        }
        data.setStrings("variables", names);
        data.setDouble(SignAngle.VARIABLE, SignAngle.encode(SignAngle.fromPlayerYaw(player.getYaw())));
        return ItemPoleComponent.PoleComponentType.createComponent(pole, player, axis, data);
    }

    /** Immersive Vehicles has just put the component on its side of the pole; turn it to its own angle instead. */
    @Inject(method = "changeComponent", at = @At("TAIL"))
    private void keystone$turn(ABlockBase.Axis axis, ATileEntityPole_Component component, CallbackInfo ci) {
        if (component == null || !axis.xzPlanar) {
            return;
        }
        double angle = SignAngle.decode(((AccessorDefinable) component).keystone$variables().get(SignAngle.VARIABLE));
        if (Double.isNaN(angle)) {
            return;
        }
        TileEntityPole pole = (TileEntityPole) (Object) this;
        component.orientation.set(pole.orientation).multiply(new RotationMatrix().setToAngles(new Point3D(0, angle, 0)));
        component.position.set(0, 0, ((JSONPoleComponent) pole.definition).pole.radius + 0.001)
                .rotate(component.orientation).add(pole.position);
        component.prevOrientation.set(component.orientation);
        component.prevPosition.set(component.position);
    }
}
