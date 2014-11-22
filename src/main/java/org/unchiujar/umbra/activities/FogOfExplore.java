/*******************************************************************************
 * This file is part of Umbra.
 *
 *     Umbra is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Umbra is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Umbra.  If not, see <http://www.gnu.org/licenses/>.
 *
 *     Copyright (c) 2011 Vasile Jureschi <vasile.jureschi@gmail.com>.
 *     All rights reserved. This program and the accompanying materials
 *     are made available under the terms of the GNU Public License v3.0
 *     which accompanies this distribution, and is available at
 *
 *    http://www.gnu.org/licenses/gpl-3.0.html
 *
 *     Contributors:
 *        Vasile Jureschi <vasile.jureschi@gmail.com> - initial API and implementation
 ******************************************************************************/

package org.unchiujar.umbra.activities;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.*;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.*;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Toast;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unchiujar.umbra.R;
import org.unchiujar.umbra.backend.ExploredProvider;
import org.unchiujar.umbra.io.GpxImporter;
import org.unchiujar.umbra.location.ApproximateLocation;
import org.unchiujar.umbra.overlays.ExploredTileProvider;
import org.unchiujar.umbra.overlays.OsmProvider;
import org.unchiujar.umbra.services.LocationService;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Timer;

import static org.unchiujar.umbra.utils.LocationUtilities.coordinatesToLocation;

/**
 * Main activity for Umbra application.
 *
 * @author Vasile Jureschi
 * @see LocationService
 */
public class FogOfExplore extends ActionBarActivity {
    /**
     * Logger tag.
     */
    private static final String TAG = FogOfExplore.class.getName();
    /**
     * Initial map zoom.
     */
    private static final int INITIAL_ZOOM = 17;
    /**
     * Constant used for saving the accuracy value between screen rotations.
     */
    private static final String BUNDLE_ACCURACY = "org.unchiujar.umbra.accuracy";
    /**
     * Constant used for saving the latitude value between screen rotations.
     */
    private static final String BUNDLE_LATITUDE = "org.unchiujar.umbra.latitude";
    /**
     * Constant used for saving the longitude value between screen rotations.
     */
    private static final String BUNDLE_LONGITUDE = "org.unchiujar.umbra.longitude";
    /**
     * Constant used for saving the zoom level between screen rotations.
     */
    private static final String BUNDLE_ZOOM = "org.unchiujar.umbra.zoom";

    /**
     * Intent named used for starting the location service
     *
     * @see LocationService
     */
    private static final String SERVICE_INTENT_NAME = "org.com.unchiujar.LocationService";
    private static final Logger LOGGER = LoggerFactory.getLogger(FogOfExplore.class);

    /**
     * Dialog displayed while loading the explored points at application start.
     */
    private ProgressDialog mLoadProgress;

    /**
     * Location service intent.
     *
     * @see LocationService
     */
    private Intent mLocationServiceIntent;

    /**
     * Source for obtaining explored area information.
     */
    private ExploredProvider mRecorder;
    /**
     * Current device latitude. Updated on every location change.
     */
    private double mCurrentLat;
    /**
     * Current device longitude. Updated on every location change.
     */
    private double mCurrentLong;
    /**
     * Current location accuracy . Updated on every location change.
     */
    private double mCurrentAccuracy;

    /**
     * Flag signaling if the user is walking or driving. It is passed to the location service in
     * order to change location update frequency.
     *
     * @see LocationService
     */
    private boolean mDrive;

    /**
     * Messenger for communicating with service.
     */
    private Messenger mService = null;
    /**
     * Flag indicating whether we have called bind on the service.
     */
    private boolean mIsBound;

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    private final Messenger mMessenger = new Messenger(new IncomingHandler());

    private SharedPreferences mSettings;

    private GoogleMap map;


    private GoogleMap.OnCameraChangeListener cameraListener = new GoogleMap.OnCameraChangeListener() {

        @Override
        public void onCameraChange(CameraPosition cameraPosition) {
            //if we are only zooming in then do nothing, the topOverlay will be scaled automatically
            redrawOverlay();
        }
    };
    private boolean overlaySwitch = true;

    /**
     * Handler of incoming messages from service.
     */
    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LocationService.MSG_LOCATION_CHANGED:
                    if (msg.obj != null) {
                        Log.d(TAG, msg.obj.toString());

                        mCurrentLat = ((Location) msg.obj).getLatitude();
                        mCurrentLong = ((Location) msg.obj).getLongitude();
                        mCurrentAccuracy = ((Location) msg.obj).getAccuracy();
                        redrawOverlay();

                    } else {
                        Log.d(TAG, "Null object received");
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * Class for interacting with the main interface of the service.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mService = new Messenger(service);
            Log.d(TAG, "Location service attached.");
            // register client
            sendMessage(LocationService.MSG_REGISTER_CLIENT);
            // register interface
            sendMessage(LocationService.MSG_REGISTER_INTERFACE);

            // send walk or drive mode
            sendMessage(mDrive ? LocationService.MSG_DRIVE
                    : LocationService.MSG_WALK);
        }

        /*
         * (non-Javadoc)
         * @see android.content.ServiceConnection#onServiceDisconnected(android.content
         * .ComponentName)
         */
        @Override
        public void onServiceDisconnected(ComponentName className) {
            // Called when the connection with the service has been
            // unexpectedly disconnected / process crashed.
            mService = null;
            Log.d(TAG, "Disconnected from location service");
        }
    };

    /**
     * Drive or walk preference listener. A listener is necessary for this option as the location
     * service needs to be notified of the change in order to change location update frequency. The
     * preference is sent when the activity comes into view and rebinds to the location service.
     */
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {

        @Override
        public void onSharedPreferenceChanged(
                SharedPreferences sharedPreferences, String key) {
            Log.d(TAG, "Settings changed :" + sharedPreferences + " " + key);
            mDrive = mSettings.getBoolean(Preferences.DRIVE_MODE, false);
        }
    };

    // ==================== LIFECYCLE METHODS ====================

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSettings = PreferenceManager.getDefaultSharedPreferences(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mLoadProgress = ProgressDialog.show(this, "", "Loading. Please wait...", true);
        mSettings.registerOnSharedPreferenceChangeListener(mPrefListener);
        setContentView(R.layout.map);


        FragmentManager fragmentManager = getFragmentManager();
        MapFragment mapFragment = (MapFragment) fragmentManager.findFragmentById(R.id.map);

        // Get a handle to the Map Fragment
        map = mapFragment.getMap();
        map.setMyLocationEnabled(true);
        map.setOnCameraChangeListener(cameraListener);
        map.setMapType(GoogleMap.MAP_TYPE_NONE);

        Log.d(TAG, "onCreate completed: Activity created");
        mLocationServiceIntent = new Intent(SERVICE_INTENT_NAME);
        startService(mLocationServiceIntent);

        // zoom and move to the current location
        Location currentLocation = map.getMyLocation();
        if (currentLocation != null) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude()), INITIAL_ZOOM));
        }

        mRecorder = ((UmbraApplication) getApplication()).getCache();
        loadFileFromIntent();
        // check we still have access to GPS info
        checkConnectivity();


        // Open street maps tiles

//        String osmUrl = "http://a.tile.openstreetmap.org/{z}/{x}/{y}.png";
//        OsmProvider osmProvider = new OsmProvider(256, 256, osmUrl);
//        osmOverlay = map.addTileOverlay(new TileOverlayOptions().tileProvider(osmProvider).zIndex(20));

        // Google satellite tiles  with roads overlaid
//        String googleUrl = "http://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}";

        // Google terrain tiles
//        String googleUrl = "http://mt1.google.com/vt/lyrs=r&x={x}&y={y}&z={z}";

        // Google terrain tile, paper map look
//        https://mts1.google.com/vt/lyrs=h@186112443&hl=x-local&src=app&x=1325&y=3143&z=13&s=Galile

        // Google terrain tile, google maps look, roads different colors depending on road ?
//        https://mts1.google.com/vt/lyrs=m@186112443&hl=x-local&src=app&x=1325&y=3143&z=13&s=Galile

        // Google terrain tile, elevation, 3Dish look with roads overlaid?
//        https://mts1.google.com/vt/lyrs=p@186112443&hl=x-local&src=app&x=1325&y=3143&z=13&s=Galile

        // Google terrain tile, elevation, 3Dish look without roads overlaid?
//        https://mts1.google.com/vt/lyrs=t@186112443&hl=x-local&src=app&x=1325&y=3143&z=13

        // Google terrain tile, google maps all roads white ?
//        https://mts1.google.com/vt/lyrs=r@186112443&hl=x-local&src=app&x=1325&y=3143&z=13&s=Galile

        // Google satellite tile, no roads ?
//        https://mts1.google.com/vt/lyrs=s@186112443&hl=x-local&src=app&x=1325&y=3143&z=13&s=Galile

        String googleUrl = "http://mt1.google.com/vt/lyrs=r&x={x}&y={y}&z={z}";
        OsmProvider googleProvider = new OsmProvider(256, 256, googleUrl);
        map.addTileOverlay(new TileOverlayOptions().tileProvider(googleProvider).zIndex(20));

        //TODO tilted overlay is not displayed correctly
        // map.getUiSettings().setTiltGesturesEnabled(false);
        // TODO rotated overlay is skewed
        // map.getUiSettings().setRotateGesturesEnabled(false);

        // Create new TileOverlayOptions instance.
        TileOverlayOptions opts = new TileOverlayOptions();
        // Set the tile provider to your custom implementation.
        provider = new ExploredTileProvider(this);
        opts.fadeIn(false).tileProvider(provider);
        // Optional. Useful if you have multiple, layered tile providers.
        opts.zIndex(30);


        // Add the tile overlay to the map.
        topOverlay = map.addTileOverlay(opts);


        // Create new TileOverlayOptions instance.
        TileOverlayOptions opts1 = new TileOverlayOptions();

        opts1.fadeIn(false).tileProvider(provider);
        // Optional. Useful if you have multiple, layered tile providers.
        opts1.zIndex(10);
        bottomOverlay = map.addTileOverlay(opts1);

//
//        //schedule overlay refresh
//        Thread timer = new Thread() {
//            public void run() {
//                for (; ; ) {
//                    try {
//                        Thread.sleep(2000);
//                    } catch (InterruptedException e) {
//                        LOGGER.warn("Thread interrupted", e);
//                    }
//                    topRefresh.sendEmptyMessage(0);
//                    try {
//                        Thread.sleep(2000);
//                    } catch (InterruptedException e) {
//                        LOGGER.warn("Thread interrupted", e);
//                    }
//                    bottomRefresh.sendEmptyMessage(0);
//
//                }
//            }
//        };
//
//        timer.start();

    }


    private Handler topRefresh = new Handler() {
        public void handleMessage(Message msg) {
            topOverlay.clearTileCache();
        }
    };


    private Handler bottomRefresh = new Handler() {
        public void handleMessage(Message msg) {
            bottomOverlay.clearTileCache();
        }
    };

    private ExploredTileProvider provider;
    private TileOverlay topOverlay;
    private TileOverlay bottomOverlay;
    private TileOverlay osmOverlay;

    /**
     * Loads a gpx data from a file path sent through an intent.
     */
    private void loadFileFromIntent() {
        //sanity checks
        Intent intent = getIntent();
        LOGGER.debug("Intent for loading data from file is {}", intent);
        // there was no intent so just return
        if (intent == null) {
            return;
        }
        Uri data = intent.getData();
        LOGGER.debug("File URI is {}", data);

        //there was no URI data so  just return
        if (data == null) {
            return;
        }

        //if we have all the data we need then load the file
        final String filePath = data.getEncodedPath();

        final ProgressDialog progress = new ProgressDialog(this);

        progress.setCancelable(false);
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progress.setMessage(getString(R.string.importing_locations));
        progress.show();

        Runnable importer = new Runnable() {

            @Override
            public void run() {
                ExploredProvider cache = ((UmbraApplication) getApplication())
                        .getCache();

                try {
                    GpxImporter.importGPXFile(new FileInputStream(
                            new File(filePath)), cache);
                } catch (ParserConfigurationException e) {
                    Log.e(TAG, "Error parsing file", e);
                } catch (SAXException e) {
                    Log.e(TAG, "Error parsing file", e);
                } catch (IOException e) {
                    Log.e(TAG, "Error reading file", e);
                }

                progress.dismiss();
            }
        };
        new Thread(importer).start();

    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        // restore accuracy and coordinates from saved state
        mCurrentAccuracy = savedInstanceState.getDouble(BUNDLE_ACCURACY);
        mCurrentLat = savedInstanceState.getDouble(BUNDLE_LATITUDE);
        mCurrentLong = savedInstanceState.getDouble(BUNDLE_LONGITUDE);

        CameraPosition cameraPosition = new CameraPosition.Builder()
                .zoom(savedInstanceState.getFloat(BUNDLE_ZOOM))
                .target(new LatLng(mCurrentLat, mCurrentLong))
                .build();

        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));

        super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // save accuracy and coordinates
        outState.putDouble(BUNDLE_ACCURACY, mCurrentAccuracy);
        outState.putDouble(BUNDLE_LATITUDE, mCurrentLat);
        outState.putDouble(BUNDLE_LONGITUDE, mCurrentLong);

        outState.putFloat(BUNDLE_ZOOM, map.getCameraPosition().zoom);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart completed: Activity started");
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.setOnCameraChangeListener(cameraListener);
        mLoadProgress.cancel();
        Log.d(TAG, "onResume completed.");
        // bind to location service
        doBindService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // unbind from service as the activity does
        // not display location info (is hidden or stopped)
        doUnbindService();
        Log.d(TAG, "onPause completed.");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop completed.");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "onRestart completed.");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy completed.");
    }

    //    // ================= END LIFECYCLE METHODS ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean result = super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        menu.findItem(R.id.settings).setIcon(
                android.R.drawable.ic_menu_preferences);
        menu.findItem(R.id.help).setIcon(android.R.drawable.ic_menu_help);
        menu.findItem(R.id.exit).setIcon(
                android.R.drawable.ic_menu_close_clear_cancel);

        return result;
    }


    /*
* (non-Javadoc)
* @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
*/
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.help:
                Log.d(TAG, "Showing help...");
                Intent helpIntent = new Intent(this, Help.class);
                startActivity(helpIntent);
                return true;
            case R.id.exit:
                Log.d(TAG, "Exit requested...");
                doUnbindService();
                // cleanup
                stopService(mLocationServiceIntent);
                mRecorder.destroy();
                finish();
                return true;
            case R.id.settings:
                Intent settingsIntent = new Intent(this, Preferences.class);
                startActivity(settingsIntent);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }


    /**
     * Updates the current location and calls an overlay redraw.
     */
    private void redrawOverlay() {
        updateExplored();
//        topOverlay.clearTileCache();

        if (!overlaySwitch) {
            bottomOverlay.setZIndex(30);
            topOverlay.setZIndex(10);
            topOverlay.clearTileCache();
        } else {

//        bottomOverlay.clearTileCache();
            topOverlay.setZIndex(30);
            bottomOverlay.setZIndex(10);
            bottomOverlay.clearTileCache();
        }

        overlaySwitch = !overlaySwitch;
    }

    private Timer timer = new Timer();


    private void updateExplored() {
        // get the coordinates of the visible area
        LatLng farLeft = map.getProjection().getVisibleRegion().farLeft;
        LatLng nearRight = map.getProjection().getVisibleRegion().nearRight;


        final ApproximateLocation upperLeft = coordinatesToLocation(farLeft);

        final ApproximateLocation bottomRight = coordinatesToLocation(nearRight);
        // TODO - optimization get points for rectangle only if a zoom out
        // or a pan action occurred - ie new points come into view

        // update the overlay with the currently visible explored area
//        OverlayFactory.getInstance(this).setExplored(mRecorder.selectVisited(upperLeft, bottomRight));


        provider.setExplored(mRecorder.selectVisited(upperLeft, bottomRight));
    }


    /**
     * Checks GPS and network connectivity. Displays a dialog asking the user to start the GPS if
     * not started and also displays a toast warning it no network connectivity is available.
     */
    private void checkConnectivity() {

        boolean isGPS = ((LocationManager) getSystemService(LOCATION_SERVICE))
                .isProviderEnabled(LocationManager.GPS_PROVIDER);

        if (!isGPS) {
            createGPSDialog().show();
        }
        displayConnectivityWarning();
    }

    /**
     * Displays a toast warning if no network is available.
     */
    private void displayConnectivityWarning() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = false;
        for (NetworkInfo info : connectivityManager.getAllNetworkInfo()) {
            if (info.getState() == NetworkInfo.State.CONNECTED
                    || info.getState() == NetworkInfo.State.CONNECTING) {
                connected = true;
                break;
            }
        }

        if (!connected) {
            Toast.makeText(getApplicationContext(),
                    R.string.connectivity_warning, Toast.LENGTH_LONG).show();

        }
    }

    /**
     * Creates the GPS dialog displayed if the GPS is not started.
     *
     * @return the GPS Dialog
     */
    private Dialog createGPSDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.gps_dialog).setCancelable(false);

        final AlertDialog alert = builder.create();

        alert.setButton(DialogInterface.BUTTON_POSITIVE,
                getString(R.string.start_gps_btn),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        alert.dismiss();
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    }
                }
        );

        alert.setButton(DialogInterface.BUTTON_NEGATIVE,
                getString(R.string.continue_no_gps),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        alert.dismiss();
                    }
                }
        );
        return alert;
    }

//    /*
//     * (non-Javadoc)
//     * @see com.google.android.maps.MapActivity#isRouteDisplayed()
//     */
//    @Override
//    protected boolean isRouteDisplayed() {
//        return false;
//    }
//

    /**
     * Binds to the location service. Called when the activity becomes visible.
     */
    private void doBindService() {
        bindService(mLocationServiceIntent, mConnection,
                Context.BIND_AUTO_CREATE);
        mIsBound = true;
        Log.d(TAG, "Binding to location service");
    }

    /**
     * Unbinds from the location service. Called when the activity is stopped or closed.
     */
    private void doUnbindService() {
        if (mIsBound) {
            // test if we have a valid service registration
            if (mService != null) {
                sendMessage(LocationService.MSG_UNREGISTER_INTERFACE);
            }

            // Detach our existing connection.
            unbindService(mConnection);
            mIsBound = false;
            Log.d(TAG, "Unbinding map from location service.");
        }
    }

    private void sendMessage(int message) {
        // TODO check message
        try {
            Message msg = Message.obtain(null, message);
            msg.replyTo = mMessenger;
            mService.send(msg);
        } catch (RemoteException e) {
            // NO-OP
            // Nothing special to do if the service
            // has crashed.
        }

    }

}
