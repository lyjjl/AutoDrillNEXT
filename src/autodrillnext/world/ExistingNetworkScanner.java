package autodrillnext.world;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.ContentId;
import autodrillnext.model.ExistingNetwork;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ExistingNetworkScanner {
    public ExistingNetwork scan(WorldSnapshot world, CapabilitySnapshot capabilities) {
        LinkedHashSet<TileKey> occupied = new LinkedHashSet<>();
        LinkedHashSet<TileKey> reusable = new LinkedHashSet<>();
        for (var entry : world.tiles().entrySet()) {
            TileState state = entry.getValue();
            if (state.existingBlock() == null) continue;
            occupied.add(entry.getKey());
            CapabilityDescriptor descriptor = capabilities.descriptors().get(ContentId.of(state.existingBlock()));
            if (descriptor != null && descriptor.kind() != CapabilityKind.UNKNOWN
                && descriptor.states().contains(autodrillnext.capability.spec.CapabilityState.SUPPORTED)) {
                reusable.add(entry.getKey());
            }
        }

        LinkedHashSet<LinkReservation> links = new LinkedHashSet<>();
        for (TileKey tile : reusable) {
            TileKey right = new TileKey(tile.x() + 1, tile.y());
            TileKey up = new TileKey(tile.x(), tile.y() + 1);
            if (reusable.contains(right)) links.add(LinkReservation.of(tile, right));
            if (reusable.contains(up)) links.add(LinkReservation.of(tile, up));
        }
        return new ExistingNetwork(links, reusable, 0f, java.util.Map.of(), occupied);
    }
}
