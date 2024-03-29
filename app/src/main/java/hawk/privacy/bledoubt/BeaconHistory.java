package hawk.privacy.bledoubt;

import android.bluetooth.BluetoothClass;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.location.Location;
import android.net.Uri;
import android.nfc.Tag;
import android.os.Environment;
import android.util.JsonReader;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import com.google.gson.JsonObject;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.service.DetectionTracker;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import androidx.lifecycle.LiveData;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import static android.provider.MediaStore.MediaColumns.MIME_TYPE;
import static android.provider.Settings.System.DATE_FORMAT;
import static androidx.core.app.ActivityCompat.startActivityForResult;


/**
 * A thread-safe wrapper around a HashTable of beaconsIds -> beaconDetection lists.
 */
public class BeaconHistory {


    private static final String TAG = "[BeaconHistory]";
    private static final String appDbName = "beacon-history";

    private HistoryDatabase db;
    private HistoryDao dao;

    private BeaconHistory(HistoryDatabase db) {
        this.db = db;
        this.dao = db.historyDao();
    }

    /**
     * TODO: Figure out if this actually wants to be a singleton. May want inheritance
     * so that we're not locked into static database lookups.
     * @return
     */
    public static BeaconHistory getAppBeaconHistory(Context context) {
        HistoryDatabase db = Room.databaseBuilder(context, HistoryDatabase.class, appDbName)
                .enableMultiInstanceInvalidation()
                .allowMainThreadQueries()
                .build();
        return new BeaconHistory(db);
    }

    public synchronized List<DeviceMetadata> getDeviceList() {
        List<DeviceMetadata> out = Arrays.asList(dao.loadAllDeviceMetadata());
        //Log.i(TAG, String.valueOf(out.size()));
        //if (out.size() > 0)
        //    for (DeviceMetadata d : out)
        //        Log.i(TAG, String.valueOf(d.bluetoothAddress));
        return out;
    }

    public synchronized LiveData<List<DeviceMetadata>> getLiveDeviceList() {
        return dao.loadAllDeviceMetadataLive();
    }

    public synchronized LiveData<List<DeviceMetadata>> getLiveSafeDeviceList() {
        return dao.loadSafeDeviceMetadataLive();
    }

    public synchronized int countSuspiciousDevices() {
        return dao.countSuspiciousDevices();
    }

    /**
     * Get the devices which have been detected in the last 20 seconds.
     *
     * Implemented based on a Stack Overflow post:
     * https://stackoverflow.com/questions/23129212/adding-subtracting-5-seconds-from-java-date-showing-deprected-warning
     */
    public synchronized LiveData<List<DeviceMetadata>> getLiveNearbyDeviceList() {
        long offset_in_milliseconds = 60*1000;
        Date a_short_time_ago = new Date();

        a_short_time_ago.setTime(a_short_time_ago.getTime() - offset_in_milliseconds);

        String timestamp_shortly_ago = TimestampConverter.toTimestamp(a_short_time_ago);
        Log.d(TAG, "Recent time " + a_short_time_ago.toString());
        Log.d(TAG, "As long: " + String.valueOf(a_short_time_ago.getTime()));
        Log.d(TAG, "Offset: " + String.valueOf(offset_in_milliseconds));
        Log.d(TAG, "As String" + timestamp_shortly_ago);
        return dao.loadDeviceMetadataSinceTimeLive(timestamp_shortly_ago);
    }
    public synchronized LiveData<List<DeviceMetadata>> getLiveSuspiciousDeviceList() {
        return dao.loadSuspiciousDeviceMetadataLive();
    }

    public synchronized List<DeviceMetadata> getSuspiciousDevices() {
        return dao.loadSuspiciousDeviceMetadata();
    }

    /**
     * Look up whether or not the user has recorded this device as safe
     * @param bluetoothAddress
     * @return isSafe
     */
    public synchronized boolean isSafe(String bluetoothAddress) {
        DeviceMetadata[] data = dao.loadMetadataForDevice(Collections.singletonList(bluetoothAddress));
        if (data.length != 0)
            return data[0].isSafe;
        return false;
    }

    /**
     * Record whether or not the user believes a particular device is safe.
     * @param bluetoothAddress the BLE address of the device
     * @param isSafe true if the device is safe and false otherwise.
     * @return success
     */
    public synchronized boolean markSafe(String bluetoothAddress, boolean isSafe) {
        DeviceMetadata[] data = dao.loadMetadataForDevice(Collections.singletonList(bluetoothAddress));
        if (data.length == 0) {
            return false;
        }
        DeviceMetadata metadata = data[0];
        metadata.isSafe = isSafe;
        dao.updateMetadata(metadata);
        return true;
    }

    /**
     * Record whether or not the user believes a particular device is suspicious.
     * @param bluetoothAddress the BLE address of the device
     * @param isSuspicious true if the device may be following the user around
     * @return success
     */
    public synchronized boolean markSuspicious(String bluetoothAddress, boolean isSuspicious) {
        DeviceMetadata[] data = dao.loadMetadataForDevice(Collections.singletonList(bluetoothAddress));
        if (data.length == 0) {
            return false;
        }
        DeviceMetadata metadata = data[0];
        metadata.isSuspicious = isSuspicious;
        dao.updateMetadata(metadata);
        return true;
    }

    /**
     * Append a new detection event onto the history of the specified beacon.
     * @param beacon: the beacon event that caused this
     * @param type
     * @param detection
     */
    public synchronized void add(Beacon beacon, BeaconType type, BeaconDetection detection) {
        dao.insertDetections(detection);
        dao.insertMetadata(new DeviceMetadata(beacon, type));
    }

    /**
     * Get a list of all detections of the given beacon.
     * @param mac: the bluetooth address of the beacon
     * @return
     */
    public synchronized Trajectory getTrajectory(String mac) {
        return new Trajectory(Arrays.asList(dao.getDetectionsForDevice(mac)));
    }


    /**
     * Remove all devices and detection events from the history.
     */
    public synchronized void clearAll() {
        dao.nukeBeaconDetections();
        dao.nukeDeviceMetadata();
    }

    @Override
    public synchronized String toString() {
        String result = "Beacon History:\n";
//        for (String beaconId : detections.keySet()) {
//            result += "Beacon ID:" + beaconId +
//                       ". Num detections: " + detections.get(beaconId).size() + ".\n";
//        }
        return result;
    }

    /**
     * @return A json object describing the state of this history.
     * @throws JSONException
     */
    public synchronized JSONObject toJSONObject() throws JSONException {
        JSONArray jsonDevices = new JSONArray();
        JSONArray jsonDetections = new JSONArray();

        for (DeviceMetadata device : dao.loadAllDeviceMetadata()) {
            jsonDevices.put(device.toJSONObject());
            for (BeaconDetection detection : dao.getDetectionsForDevice(device.bluetoothAddress)) {
                jsonDetections.put(detection.toJSONObject());
            }
        }

        JSONObject root_object = new JSONObject();
        root_object.put("devices", jsonDevices);
        root_object.put("detections", jsonDetections);
        return root_object;
    }

    /**
     * Construct database from JSON object. Destroys current contents of database if they exist.
     */
    public synchronized void loadJSON(JSONObject object) throws JSONException {
        dao.nukeDeviceMetadata();
        dao.nukeBeaconDetections();

        JSONArray jsonMetadata = object.getJSONArray("devices");
        DeviceMetadata[] metadata = new DeviceMetadata[jsonMetadata.length()];
        for (int i = 0; i < jsonMetadata.length(); i++) {
            metadata[i] = DeviceMetadata.fromJSONObject(jsonMetadata.getJSONObject(i));
        }
        dao.insertMetadata(metadata);

        JSONArray jsonDetections = object.getJSONArray("detections");
        BeaconDetection[] detections = new BeaconDetection[jsonDetections.length()];
        for (int i = 0; i < jsonDetections.length(); i++) {
            detections[i] = BeaconDetection.fromJSONObject(jsonDetections.getJSONObject(i));
        }
        dao.insertDetections(detections);
    }

}

@Database(entities = {DeviceMetadata.class, BeaconDetection.class}, version = 1)
abstract class HistoryDatabase extends RoomDatabase {
    public abstract HistoryDao historyDao();
}