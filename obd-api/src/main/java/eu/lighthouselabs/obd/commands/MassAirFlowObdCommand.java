/*
 * TODO put header
 */
package eu.lighthouselabs.obd.commands;

/**
 * TODO put description
 * 
 * Mass Air Flow
 */
public class MassAirFlowObdCommand extends OBDCommand {

	private double maf = -9999.0;

	/**
	 * Default ctor.
	 */
	public MassAirFlowObdCommand() {
		super("01 10");
	}

	/**
	 * Copy ctor.
	 * 
	 * @param other
	 */
	public MassAirFlowObdCommand(MassAirFlowObdCommand other) {
		super(other);
	}

	/**
	 * 
	 */
	@Override
	public String getFormattedResult() {
		String res = getResult();

		if (!"NODATA".equals(res)) {
			// ignore first two bytes [hh hh] of the response
			byte b1 = Byte.parseByte(res.substring(4, 6));
			byte b2 = Byte.parseByte(res.substring(6, 8));
			maf = ((b1 << 8) | b2) / 100.0;
			res = String.format("%.2f %s", maf, "grams/sec");
		}

		return res;
	}

	/**
	 * @return MAF value for further calculus.
	 */
	double getMAF() {
		return maf;
	}
}