package smartcharge.workload;

import smartcharge.model.ChargingRequest;

/** A request plus its arrival offset from the start of the run, in simulated minutes. */
public record WorkloadItem(double arrivalSimMinutes, ChargingRequest request) {
}
