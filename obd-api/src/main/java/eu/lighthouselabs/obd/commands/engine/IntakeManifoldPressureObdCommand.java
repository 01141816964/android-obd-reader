/*
 * TODO put header
 */
package eu.lighthouselabs.obd.commands.engine;

import eu.lighthouselabs.obd.commands.PressureObdCommand;

/**
 * 
 * TODO put description
 * 
 * Intake Manifold Press
 */
public class IntakeManifoldPressureObdCommand extends PressureObdCommand {

	/**
	 * Default ctor.
	 */
	public IntakeManifoldPressureObdCommand() {
		super("01 0B");
	}

	/**
	 * Copy ctor.
	 * 
	 * @param other
	 */
	public IntakeManifoldPressureObdCommand(
			IntakeManifoldPressureObdCommand other) {
		super(other);
	}

	/**
	 * 
	 */
	@Override
	protected int preparePressureValue() {
		// just return tempValue as no calculations are needed
		return tempValue;
	}

	@Override
	public String getName() {
		return "Intake Manifold Pressure";
	}
}