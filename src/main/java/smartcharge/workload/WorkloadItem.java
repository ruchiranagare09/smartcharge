/*
 * SmartCharge - CO3408 Advanced Software Modelling
 * Name: <STUDENT NAME>
 * Student ID: <STUDENT ID>
 */
package smartcharge.workload;

import smartcharge.model.ChargingRequest;

/** A request plus its arrival offset from the start of the run, in simulated minutes. */
public record WorkloadItem(double arrivalSimMinutes, ChargingRequest request) {
}
