package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.SupportVariant;
import autodrillnext.model.ContentId;
import autodrillnext.model.ItemId;
import autodrillnext.model.LiquidId;
import autodrillnext.model.SupportRequirement;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquidFilter;
import mindustry.world.consumers.ConsumePower;

import java.util.LinkedHashMap;
import java.util.Map;

final class AdapterSupport {
    private AdapterSupport() {
    }

    static ContentId id(Block block) {
        return ContentId.of(block.name);
    }

    static CostVector cost(Block block, CapabilityContext context) {
        LinkedHashMap<ItemId, Integer> amounts = new LinkedHashMap<>();
        if (block.requirements != null) {
            for (ItemStack requirement : block.requirements) {
                if (requirement != null && requirement.item != null && requirement.amount > 0) {
                    amounts.merge(ItemId.of(requirement.item.name),
                        Math.round(requirement.amount * context.buildCostMultiplier()), Math::addExact);
                }
            }
        }
        return CostVector.of(amounts, context.resourceValuation());
    }

    static SupportRequirements supports(Block block, CapabilityContext context, float boostIntensity) {
        block.reinitializeConsumers();
        LinkedHashMap<Integer, SupportVariant> optional = new LinkedHashMap<>();
        java.util.ArrayList<SupportRequirement> mandatory = new java.util.ArrayList<>();
        if (block.consumers == null) return new SupportRequirements(mandatory, java.util.List.of());

        int variantIndex = 0;
        for (Consume consume : block.consumers) {
            SupportRequirement requirement = requirement(consume, context);
            if (requirement == null) continue;
            if (!consume.optional) {
                mandatory.add(requirement);
            } else {
                optional.put(variantIndex++, new SupportVariant(
                    ContentId.of(block.name + ":support-" + variantIndex),
                    requirement,
                    Math.max(1f, 1f + boostIntensity)
                ));
            }
        }
        return new SupportRequirements(mandatory, new java.util.ArrayList<>(optional.values()));
    }

    private static SupportRequirement requirement(Consume consume, CapabilityContext context) {
        if (consume instanceof ConsumePower power) {
            SupportRequirement.Kind kind = consume.booster
                ? SupportRequirement.Kind.BOOSTER
                : SupportRequirement.Kind.POWER;
            return new SupportRequirement(kind, java.util.List.of(), positiveOrZero(power.usage * 60f), !consume.optional);
        }
        if (consume instanceof ConsumeLiquid liquid) {
            return liquidRequirement(
                consume,
                java.util.List.of(LiquidId.of(liquid.liquid.name)),
                liquid.amount * 60f
            );
        }
        if (consume instanceof ConsumeLiquidFilter filter) {
            java.util.ArrayList<LiquidId> allowed = new java.util.ArrayList<>();
            for (Liquid liquid : context.liquids()) {
                if (filter.filter != null && filter.filter.get(liquid)) allowed.add(LiquidId.of(liquid.name));
            }
            if (allowed.isEmpty() && !consume.optional) {
                throw new IllegalArgumentException("mandatory liquid filter has no registered matching liquid");
            }
            return allowed.isEmpty() ? null : liquidRequirement(consume, allowed, filter.amount * 60f);
        }
        return null;
    }

    private static SupportRequirement liquidRequirement(
        Consume consume,
        java.util.List<LiquidId> allowed,
        float amount
    ) {
        SupportRequirement.Kind kind = consume.booster
            ? SupportRequirement.Kind.BOOSTER
            : SupportRequirement.Kind.LIQUID;
        return new SupportRequirement(kind, allowed, positiveOrZero(amount), !consume.optional);
    }

    static float positiveOrZero(float value) {
        return Float.isFinite(value) && value > 0f ? value : 0f;
    }
}
