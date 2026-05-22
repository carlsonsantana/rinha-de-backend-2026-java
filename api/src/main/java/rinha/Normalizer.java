package rinha;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Normalizer {

    public final double maxAmount;
    public final double maxInstallments;
    public final double amountVsAvgRatio;
    public final double maxMinutes;
    public final double maxKm;
    public final double maxTxCount24h;
    public final double maxMerchantAvgAmount;

    private final Map<String, Double> mccRisk;

    private Normalizer(double maxAmount, double maxInstallments, double amountVsAvgRatio,
                       double maxMinutes, double maxKm, double maxTxCount24h,
                       double maxMerchantAvgAmount, Map<String, Double> mccRisk) {
        this.maxAmount            = maxAmount;
        this.maxInstallments      = maxInstallments;
        this.amountVsAvgRatio     = amountVsAvgRatio;
        this.maxMinutes           = maxMinutes;
        this.maxKm                = maxKm;
        this.maxTxCount24h        = maxTxCount24h;
        this.maxMerchantAvgAmount = maxMerchantAvgAmount;
        this.mccRisk              = mccRisk;
    }

    public double mccRisk(String mcc) {
        Double v = mccRisk.get(mcc);
        return v != null ? v : 0.5;
    }

    public static double clamp(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    public static Normalizer load(Path normPath, Path mccPath) throws IOException {
        String normJson = Files.readString(normPath);
        double maxAmount            = readDouble(normJson, "max_amount");
        double maxInstallments      = readDouble(normJson, "max_installments");
        double amountVsAvgRatio     = readDouble(normJson, "amount_vs_avg_ratio");
        double maxMinutes           = readDouble(normJson, "max_minutes");
        double maxKm                = readDouble(normJson, "max_km");
        double maxTxCount24h        = readDouble(normJson, "max_tx_count_24h");
        double maxMerchantAvgAmount = readDouble(normJson, "max_merchant_avg_amount");

        String mccJson = Files.readString(mccPath);
        Map<String, Double> mccRisk = readStringDoubleMap(mccJson);

        return new Normalizer(maxAmount, maxInstallments, amountVsAvgRatio,
                              maxMinutes, maxKm, maxTxCount24h, maxMerchantAvgAmount, mccRisk);
    }

    private static double readDouble(String json, String key) {
        String search = "\"" + key + "\"";
        int i = json.indexOf(search);
        if (i < 0) throw new IllegalArgumentException("missing key: " + key);
        i += search.length();
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ':')) i++;
        int s = i;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-') i++;
            else break;
        }
        return Double.parseDouble(json.substring(s, i));
    }

    private static Map<String, Double> readStringDoubleMap(String json) {
        Map<String, Double> map = new HashMap<>();
        int i = json.indexOf('{') + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c <= ' ' || c == ',') { i++; continue; }
            if (c == '}') break;
            if (c == '"') {
                i++;
                int ks = i;
                while (i < json.length() && json.charAt(i) != '"') i++;
                String key = json.substring(ks, i++);
                while (i < json.length() && (json.charAt(i) <= ' ' || json.charAt(i) == ':')) i++;
                int vs = i;
                while (i < json.length()) {
                    char d = json.charAt(i);
                    if (Character.isDigit(d) || d == '.' || d == '-') i++;
                    else break;
                }
                map.put(key, Double.parseDouble(json.substring(vs, i)));
            } else {
                i++;
            }
        }
        return map;
    }
}
