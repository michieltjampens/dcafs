package util.math;

public class Calculations {

    static final double[] a = { 0.0080, -0.1692, 25.3851, 14.0941, -7.0261, 2.7081 };/* constants for salinity calculation */

    static final double[] b = { 0.0005, -0.0056, -0.0066, -0.0375, 0.0636, -0.0144 };/* constants for salinity calculation */

    static final double A1 = 2.070e-5, A2 = -6.370e-10, A3 = 3.989e-15, B1 = 3.426e-2, B2 = 4.464e-4, B3 = 4.215e-1,
            B4 = -3.107e-3, C0 = 6.766097e-1, C1 = 2.00564e-2, C2 = 1.104259e-4, C3 = -6.9698e-7, C4 = 1.0031e-9;

    /**
     * Method that calculates the salinity based on CTP measurements
     *
     * @param C The conductivity in Siemens/meter
     * @param T The temperature in °C
     * @param P The pressure in dB
     * @return Salinity in PSU
     */
    public static double calcSalinity(double C, double T, double P) {
        /* compute salinity */
        // C = conductivity S/m, T = temperature deg C ITPS-68, P = pressure in decibars

        double R, RT = 0.0, RP = 0.0, temp, sum1, sum2, result, val;
        int i;
        if (C <= 0.0) {
            result = 0.0;
        } else {
            C *= 10.0; /* convert Siemens/meter to mmhos/cm */
            T *= 1.00024; /* convert ITS90 to ITS68 */
            R = C / 42.914;
            val = 1 + B1 * T + B2 * T * T + B3 * R + B4 * R * T;
            if (!Double.isNaN(val)) {
                RP = 1 + (P * (A1 + P * (A2 + P * A3))) / val;
            }
            val = RP * (C0 + (T * (C1 + T * (C2 + T * (C3 + T * C4)))));
            if (!Double.isNaN(val)) {
                RT = R / val;
            }
            if (RT <= 0.0) {
                RT = 0.000001;
            }
            sum1 = sum2 = 0.0;
            for (i = 0; i < 6; i++) {
                temp = Math.pow(RT, (double) i / 2.0);
                sum1 += a[i] * temp;
                sum2 += b[i] * temp;
            }
            val = 1.0 + 0.0162 * (T - 15.0);
            if (!Double.isNaN(val)) {
                result = sum1 + sum2 * (T - 15.0) / val;
            } else {
                result = -99.;
            }
        }
        return result;
    }

    /**
     * Calculate the Sound Velocity according to Chen and Millero
     * 
     * @param salinity The salinity in PSU
     * @param temp     The temperature in degrees celsius
     * @param pressDB  The pressure in DB
     * @return The calculated soundvelocity in m/s
     */
    public static double calcSndVelC(double salinity, double temp, double pressDB) {
        /* sound velocity Chen and Millero 1977 */
        /* JASA,62,1129-1135 */
        // s = salinity, t = temperature deg C ITS-90, p = pressure in decibars

        if (temp == -999 || salinity == -999) {
            return -999;
        }

        double a, a0, a1, a2, a3;
        double b, b0, b1;
        double c, c0, c1, c2, c3;
        double p, sr, d, sv;

        temp *= 1.00024; /* ITS90 to ITS68 */
        p = pressDB / 10.0; /* scale pressure to bars */
        if (salinity < 0.0) {
            salinity = 0.0;
        }
        sr = Math.sqrt(salinity);
        d = 1.727e-3 - 7.9836e-6 * p;
        b1 = 7.3637e-5 + 1.7945e-7 * temp;
        b0 = -1.922e-2 - 4.42e-5 * temp;
        b = b0 + b1 * p;
        a3 = (-3.389e-13 * temp + 6.649e-12) * temp + 1.100e-10;
        a2 = ((7.988e-12 * temp - 1.6002e-10) * temp + 9.1041e-9) * temp - 3.9064e-7;
        a1 = (((-2.0122e-10 * temp + 1.0507e-8) * temp - 6.4885e-8) * temp - 1.2580e-5) * temp + 9.4742e-5;
        a0 = (((-3.21e-8 * temp + 2.006e-6) * temp + 7.164e-5) * temp - 1.262e-2) * temp + 1.389;
        a = ((a3 * p + a2) * p + a1) * p + a0;
        c3 = (-2.3643e-12 * temp + 3.8504e-10) * temp - 9.7729e-9;
        c2 = (((1.0405e-12 * temp - 2.5335e-10) * temp + 2.5974e-8) * temp - 1.7107e-6) * temp + 3.1260e-5;
        c1 = (((-6.1185e-10 * temp + 1.3621e-7) * temp - 8.1788e-6) * temp + 6.8982e-4) * temp + 0.153563;
        c0 = ((((3.1464e-9 * temp - 1.47800e-6) * temp + 3.3420e-4) * temp - 5.80852e-2) * temp + 5.03711) * temp
                + 1402.388;
        c = ((c3 * p + c2) * p + c1) * p + c0;
        sv = c + (a + b * sr + d * salinity) * salinity;
        return sv;
    }

    /**
     * Method that calculates the true windvelocity based on apparent wind and ships
     * navigation Source: <a href="http://coaps.fsu.edu/woce/truewind/paper/">...</a>
     *
     * @param windvel The apparent windvelocity in m/s
     * @param winddir The apparent wind direction in degrees [°]
     * @param sogKnots The Speed Over Ground in Knots
     * @param cog     The Course over Ground in degrees [°]
     * @param heading The ships heading in degrees [°]
     * @return The True Wind Velocity in m/s
     */
    public static double calcTrueWindVelocity(double windvel, double winddir, double sogKnots, double cog,
            double heading) {
        double dev = 0;
        if( cog <0 )
            cog=heading;
        double app = Math.toRadians(270 - (heading + dev + winddir));
        double course = Math.toRadians(90 - cog);
        double sogms = sogKnots * 0.5144444;

        double tx = windvel * Math.cos(app) + sogms * Math.cos(course);
        double ty = windvel * Math.sin(app) + sogms * Math.sin(course);

        return MathUtils.roundDouble(Math.sqrt(tx * tx + ty * ty), 5);
    }

    /**
     * Method that calculates the True wind direction based on apparent wind and
     * ships navigation Source: <a href="http://coaps.fsu.edu/woce/truewind/paper/">...</a>
     *
     * @param windvel The apparent windvelocity in m/s
     * @param winddir The apparent wind direction in degrees [°]
     * @param sogKnots The Speed Over Ground in Knots
     * @param cog     The Course over Ground in degrees [°]
     * @param heading The ships heading in degrees [°]
     * @return The Meteorological True Wind Direction in degrees
     */
    public static double calcTrueWindDirection(double windvel, double winddir, double sogKnots, double cog,
            double heading) {
        double dev = 0;
        if( cog < 0 ) {
            cog = heading;
        }
        double app = Math.toRadians(270 - (heading + dev + winddir));
        double course = Math.toRadians(90 - cog);
        double sogms = sogKnots * 0.5144444;

        double Tu = windvel * Math.cos(app) + sogms * Math.cos(course);
        double Tv = windvel * Math.sin(app) + sogms * Math.sin(course);

        double Truedir = -999;
        if (Tu != 0 && Tv != 0) {
            Truedir = MathUtils.roundDouble(270 - Math.toDegrees(Math.atan2(Tv, Tu)), 1);

            while (Truedir > 360) {
                Truedir -= 360;
            }

            while (Truedir < 0) {
                Truedir += 360;
            }

        }
        return Truedir;
    }
}
