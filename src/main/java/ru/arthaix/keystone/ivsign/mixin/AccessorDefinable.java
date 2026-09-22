package ru.arthaix.keystone.ivsign.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import minecrafttransportsimulator.entities.components.AEntityD_Definable;

@Mixin(value = AEntityD_Definable.class, remap = false)
public interface AccessorDefinable {
    @Accessor("variables")
    Map<String, Double> keystone$variables();
}
