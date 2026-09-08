package autodrillnext.simulation;

public record SimulationResult(
    SimulationFidelity fidelity,
    float simulatedQout,
    float steadyStateQout,
    boolean steadyStateDetected,
    float drillBlockedRatio,
    float transportBlockedRatio,
    float exitBurstiness,
    float queueOccupancy,
    int simulatedTicks
) {
    public SimulationResult {
        if (fidelity == null) throw new NullPointerException("fidelity");
        if (simulatedTicks < 0 || (fidelity != SimulationFidelity.UNSUPPORTED && simulatedTicks < 1)) {
            throw new IllegalArgumentException("simulated ticks");
        }
        validate(simulatedQout, "simulated qout");
        validate(steadyStateQout, "steady-state qout");
        validateRatio(drillBlockedRatio, "drill blocked ratio");
        validateRatio(transportBlockedRatio, "transport blocked ratio");
        validate(exitBurstiness, "exit burstiness");
        validateRatio(queueOccupancy, "queue occupancy");
    }
    public static SimulationResult unsupported() {
        return new SimulationResult(SimulationFidelity.UNSUPPORTED, 0f, 0f, false, 0f, 0f, 0f, 0f, 0);
    }


    private static void validate(float value, String name) {
        if (!Float.isFinite(value) || value < 0f) throw new IllegalArgumentException(name);
    }

    private static void validateRatio(float value, String name) {
        if (!Float.isFinite(value) || value < 0f || value > 1f) throw new IllegalArgumentException(name);
    }
}
