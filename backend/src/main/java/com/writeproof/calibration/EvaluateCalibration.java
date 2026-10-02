package com.writeproof.calibration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline evaluation, no server needed:
 * <pre>
 * java -Dloader.main=com.writeproof.calibration.EvaluateCalibration \
 *      -cp writeproof-backend.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *      dataset.json [threshold=0.5] [targetFar=0.01] [enrolSize=3]
 * </pre>
 * Prints a Markdown report; writes {@code dataset.json.report.json} with the full curve.
 */
public final class EvaluateCalibration {

    private EvaluateCalibration() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: EvaluateCalibration dataset.json [threshold] [targetFar] [enrolSize]");
            System.exit(2);
        }
        double threshold = args.length > 1 ? Double.parseDouble(args[1]) : 0.5;
        double targetFar = args.length > 2 ? Double.parseDouble(args[2]) : 0.01;
        int enrolSize = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        CalibrationDataset dataset = json.readValue(Path.of(args[0]).toFile(), CalibrationDataset.class);

        CalibrationEvaluator.Report report = new CalibrationEvaluator(enrolSize, threshold, targetFar).evaluate(dataset);

        System.out.println(CalibrationReportWriter.markdown(report, threshold));
        Files.writeString(Path.of(args[0] + ".report.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
