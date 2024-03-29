package hawk.privacy.bledoubt;

import android.content.Context;
import android.util.Log;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


public class HistoryAnalyzer extends Worker {
    static final double MAX_BLE_RANGE_M = 10;

    boolean analysisEnabled = true;
    TopologicalClassifier classifier = new TopologicalClassifier(60, 300, 300);

    public static class TopologicalClassifier {
        protected final float epsilonSeconds;
        protected final float minDiameterMeters;
        protected final float minDurationSeconds;

        public TopologicalClassifier(float epsilonSeconds, float minDiameterMeters, float minDurationSeconds) {
            this.epsilonSeconds = epsilonSeconds;
            this.minDiameterMeters = minDiameterMeters;
            this.minDurationSeconds = minDurationSeconds;
        }

        public boolean isSuspicious(Trajectory trajectory) {
            List<Trajectory> epsilon_components = trajectory.getEpsilonComponents(epsilonSeconds);
            for (Trajectory component : epsilon_components) {
                if (component.getDurationInSeconds() > minDurationSeconds &&
                        (component.getDiameterLowerBoundInMeters() > minDiameterMeters ||
                         component.getDiameterInMeters() > minDiameterMeters)
                )  {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * When enabled, suspends history analysis for dataset collection. This makes it easier to
     * collect data without interruptions from notifications.
     * @param loggingModeEnabled
     */
    public void setLoggingMode(boolean loggingModeEnabled) {
        this.analysisEnabled = !loggingModeEnabled;
    }

    public static final String TAG = "[HistoryAnalyzer]";

    @NonNull
    Context context;
    public HistoryAnalyzer(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
    }



    @Override
    public Result doWork() {
        if (analysisEnabled) {
            return analyze(context, classifier);
        } else {
            Log.i(TAG, "Skipping analysis in logging mode.");
            return Result.success();
        }
    }


    public static Result analyze(Context context, TopologicalClassifier classifier) {
        Log.i(TAG, "Analyzing");
        BeaconHistory history = BeaconHistory.getAppBeaconHistory(context);
        List<DeviceMetadata> devices = history.getDeviceList();

        for (DeviceMetadata metadata : devices) {
            Trajectory trajectory = BeaconHistory.getAppBeaconHistory(context).getTrajectory(metadata.bluetoothAddress);
            if (classifier.isSuspicious(trajectory)) {
                history.markSuspicious(metadata.bluetoothAddress, true);
            }
        }

        int suspiciousCount = history.countSuspiciousDevices();

        if (suspiciousCount == 1) {
            Notifications.getInstance().createSuspiciousDeviceNotification(context, BeaconHistory.getAppBeaconHistory(context).getSuspiciousDevices().get(0));
        } else if (suspiciousCount > 1) {
            Notifications.getInstance().createSuspiciousMultiDeviceNotification(context, suspiciousCount);
        }
       return Result.success();
    }

}
