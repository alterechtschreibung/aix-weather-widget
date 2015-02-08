package net.veierland.aix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

import net.veierland.aix.AixProvider.AixForecasts;
import net.veierland.aix.AixProvider.AixForecastsColumns;
import net.veierland.aix.AixProvider.AixLocations;
import net.veierland.aix.AixProvider.AixLocationsColumns;
import net.veierland.aix.AixProvider.AixSettingsColumns;
import net.veierland.aix.AixProvider.AixViews;
import net.veierland.aix.AixProvider.AixWidgetSettings;
import net.veierland.aix.AixProvider.AixWidgets;
import net.veierland.aix.AixProvider.AixWidgetsColumns;
import net.veierland.aix.widget.AixDetailedWidget;
import net.veierland.aix.widget.AixWidgetDrawException;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.IInterface;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Xml;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.RemoteViews;

public class AixService extends IntentService {
	
	private static final String TAG = "AixService";
	
	public final static String APPWIDGET_ID = "appWidgetId";
	
	public final static String ACTION_DELETE_WIDGET = "aix.intent.action.DELETE_WIDGET";
	public final static String ACTION_UPDATE_ALL = "aix.intent.action.UPDATE_ALL";
	public final static String ACTION_UPDATE_WIDGET = "aix.intent.action.UPDATE_WIDGET";
	
	public AixService() {
		super("net.veierland.aix.AixService");
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		String action = intent.getAction();
		Uri widgetUri = intent.getData();
		ContentResolver resolver = getApplicationContext().getContentResolver();
		
		Log.d(TAG, "action=" + action + ", widgetUri=" + widgetUri);
		
		if (action.equals(ACTION_UPDATE_WIDGET)) {
			updateWhatever(widgetUri);
		} else if (action.equals(ACTION_UPDATE_ALL)) {
			AppWidgetManager manager = AppWidgetManager.getInstance(this);
			int[] appWidgetIds = manager.getAppWidgetIds(new ComponentName(this, AixWidget.class));
			for (int appWidgetId : appWidgetIds) {
				updateWhatever(ContentUris.withAppendedId(AixWidgets.CONTENT_URI, appWidgetId));
			}
		} else if (action.equals(ACTION_DELETE_WIDGET)) {
			// TODO also cancel scheduled intent..
			Cursor widgetCursor = resolver.query(widgetUri, null, null, null, null);
			
			if (widgetCursor != null) {
				Uri viewUri = null;
				if (widgetCursor.moveToFirst()) {
					long viewRowId = widgetCursor.getLong(AixWidgetsColumns.VIEWS_COLUMN);
					if (viewRowId != -1) {
						viewUri = ContentUris.withAppendedId(AixViews.CONTENT_URI, viewRowId);
					}
				}
				widgetCursor.close();
				if (viewUri != null) {
					resolver.delete(viewUri, null, null);
				}
				resolver.delete(widgetUri, null, null);
			}
			
			int widgetId = (int)ContentUris.parseId(widgetUri);
			resolver.delete(AixWidgetSettings.CONTENT_URI, AixSettingsColumns.ROW_ID + '=' + widgetId, null);
			
			File dir = getCacheDir();
			File[] files = dir.listFiles();
				
			for (int i = 0; i < files.length; i++) {
				File f = files[i];
				String[] s = f.getName().split("_");
				if (s.length == 4 && s[1].equals(Integer.toString(widgetId))) {
					f.delete();
				}
			}
		}
	}
	
	private void updateWhatever(Uri widgetUri) {
		ContentResolver resolver = getApplicationContext().getContentResolver();
		
		int widgetId = (int)ContentUris.parseId(widgetUri);
		
		long utcTime = Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis();
		long updateTime = Long.MAX_VALUE;
		
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		
		int updateHours = 0;
		String updateRateString = settings.getString(getString(R.string.update_rate_string), "0");
		try {
			updateHours = Integer.parseInt(updateRateString);
		} catch (NumberFormatException e) { }
		
		boolean wifiOnly = settings.getBoolean(getString(R.string.wifi_only_bool), false);

		boolean widgetFound = false;
		String widgetTitle = null, widgetViews = null;
		int widgetSize = -1;
		
		Cursor widgetCursor = resolver.query(widgetUri, null, null, null, null);
		if (widgetCursor != null) {
			if (widgetCursor.moveToFirst()) {
				widgetSize = widgetCursor.getInt(AixWidgetsColumns.SIZE_COLUMN);
				widgetViews = widgetCursor.getString(AixWidgetsColumns.VIEWS_COLUMN);
				widgetFound = true;
			}
			widgetCursor.close();
		}
		
		Uri viewUri = null;
		boolean weatherUpdated = false;
		boolean locationFound = false;
		long lastForecastUpdate = -1, forecastValidTo = -1, nextForecastUpdate = -1;
		boolean shouldUpdate = false;
		
		if (!widgetFound) {
			// Draw error widget
			Log.d(TAG, "Error: Could not find widget in database. uri=" + widgetUri);
		} else {
			
			int layout;
			
//			switch (widgetSize) {
//			case AixWidgets.SIZE_LARGE_SMALL:
//				layout = R.layout.widget_large_small;
//				break;
//			default:
				layout = R.layout.widget;
				//break;
			//}
			
			long viewId = Long.parseLong(widgetViews);
			viewUri = ContentUris.withAppendedId(AixViews.CONTENT_URI, viewId);
			
			Cursor locationCursor = resolver.query(
					Uri.withAppendedPath(viewUri, AixViews.TWIG_LOCATION),
					null, null, null, null);
			
			if (locationCursor != null) {
				if (locationCursor.moveToFirst()) {
					lastForecastUpdate = locationCursor.getLong(AixLocationsColumns.LAST_FORECAST_UPDATE_COLUMN);
					forecastValidTo = locationCursor.getLong(AixLocationsColumns.FORECAST_VALID_TO_COLUMN);
					nextForecastUpdate = locationCursor.getLong(AixLocationsColumns.NEXT_FORECAST_UPDATE_COLUMN);
					locationFound = true;
				}
				locationCursor.close();
			}

			if (locationFound) {
				if (updateHours == 0) {
					if (utcTime >= lastForecastUpdate + DateUtils.HOUR_IN_MILLIS &&
							(utcTime >= nextForecastUpdate || forecastValidTo < utcTime))
					{
						shouldUpdate = true;
					}
				} else {
					if (utcTime >= lastForecastUpdate + updateHours * DateUtils.HOUR_IN_MILLIS) {
						shouldUpdate = true;
					}
				}

				if (shouldUpdate) {
					if (wifiOnly) {
						ConnectivityManager connManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
						NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
						if (mWifi.isConnected()) {
							try {
								updateWeather(resolver, viewUri);
								weatherUpdated = true;
								Log.d(TAG, "updateWeather() successful!");
							} catch (Exception e) {
								Log.d(TAG, "updateWeather() failed! Scheduling update in 5 minutes");
								updateTime = calcUpdateTime(updateTime, System.currentTimeMillis() + 5 * DateUtils.MINUTE_IN_MILLIS);
							}
						} else {
							Editor editor = settings.edit();
							editor.putBoolean("needwifi", true);
							editor.commit();
							Log.d(TAG, "WiFi needed, but not connected!");
						}
					} else {
						try {
							updateWeather(resolver, viewUri);
							weatherUpdated = true;
							Log.d(TAG, "updateWeather() successful!");
						} catch (Exception e) {
							Log.d(TAG, "updateWeather() failed! Scheduling update in 5 minutes");
							ContentValues values = new ContentValues();
							values.put(AixLocationsColumns.LAST_FORECAST_UPDATE, 0);
							values.put(AixLocationsColumns.FORECAST_VALID_TO, 0);
							values.put(AixLocationsColumns.NEXT_FORECAST_UPDATE, 0);
							resolver.update(Uri.withAppendedPath(viewUri, AixViews.TWIG_LOCATION), values, null, null);
							updateTime = calcUpdateTime(updateTime, System.currentTimeMillis() + 5 * DateUtils.MINUTE_IN_MILLIS);
						}
					}
				}
			}
			
			if (!widgetFound || !locationFound) {
				if (widgetFound && !locationFound) {
					updateWidget(widgetUri, getString(R.string.widget_load_error_please_recreate), layout);
				}
			} else {
				try {
					long now = System.currentTimeMillis();
					
					try {
						String portraitFileName = "aix_" + widgetId + "_" + now + "_portrait.png";
						
						Bitmap bitmap = AixDetailedWidget.buildView(
								getApplicationContext(), widgetUri, viewUri, false);
						File file = new File(getCacheDir(), portraitFileName);
						FileOutputStream out = new FileOutputStream(file);
						bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
						out.flush();
						out.close();
						
						String landscapeFileName = "aix_" + widgetId + "_" + now + "_landscape.png";
						bitmap = AixDetailedWidget.buildView(
								getApplicationContext(), widgetUri, viewUri, true);
						file = new File(getCacheDir(), landscapeFileName);
						out = new FileOutputStream(file);
						bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
						out.flush();
						out.close();
						
						Uri uri = Uri.parse("content://net.veierland.aix/aixrender/" + widgetId + "/" + now);
						updateWidget(widgetId, widgetUri, uri, layout);
					} catch (AixWidgetDrawException e) {
						switch (e.getErrorCode()) {
						case AixWidgetDrawException.INVALID_WIDGET_SIZE:
							updateWidget(widgetUri, getString(R.string.widget_load_error_please_recreate), layout);
							return;
						default:
							updateWidget(widgetUri, getString(R.string.widget_no_weather_data), layout);
							ContentValues values = new ContentValues();
							values.put(AixLocationsColumns.LAST_FORECAST_UPDATE, 0);
							values.put(AixLocationsColumns.FORECAST_VALID_TO, 0);
							values.put(AixLocationsColumns.NEXT_FORECAST_UPDATE, 0);
							resolver.update(Uri.withAppendedPath(viewUri, AixViews.TWIG_LOCATION), values, null, null);
							updateTime = calcUpdateTime(updateTime, System.currentTimeMillis() + 5 * DateUtils.MINUTE_IN_MILLIS);
							break;
						}
						Log.d(TAG, e.toString());
						e.printStackTrace();
					}
				} catch (Exception e) {
					updateWidget(widgetUri, getString(R.string.widget_failed_to_draw), layout);
					e.printStackTrace();
				}
			}
			
			// Schedule next update
			Calendar calendar = Calendar.getInstance();
			calendar.set(Calendar.MINUTE, 0);
			calendar.set(Calendar.SECOND, 0);
			calendar.set(Calendar.MILLISECOND, 0);
			calendar.add(Calendar.HOUR, 1);
			
			// Add random interval to spread traffic
			calendar.add(Calendar.SECOND, (int)(120.0f * Math.random()));
			
			updateTime = calcUpdateTime(updateTime, calendar.getTimeInMillis());
			
			Intent updateIntent = new Intent(
					AixService.ACTION_UPDATE_WIDGET, widgetUri,
					getApplicationContext(), AixService.class);
			updateIntent.setClass(getApplicationContext(), AixService.class);
			
			PendingIntent pendingUpdateIntent =
				PendingIntent.getService(getApplicationContext(), 0, updateIntent, 0);
			
			boolean awakeOnly = settings.getBoolean(getString(R.string.awake_only_bool), false);
			
			AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
			alarmManager.set(awakeOnly ? AlarmManager.RTC : AlarmManager.RTC_WAKEUP, updateTime, pendingUpdateIntent);
			Log.d(TAG, "Scheduling next update for: " + (new SimpleDateFormat().format(updateTime)) + " AwakeOnly=" + awakeOnly);
		}
	}
	
	private void updateWidget(int widgetId, Uri widgetUri, Uri imageUri, int layoutId) {
		RemoteViews updateView = new RemoteViews(getPackageName(), layoutId);
		updateView.setViewVisibility(R.id.widgetTextContainer, View.GONE);
		updateView.setViewVisibility(R.id.widgetImageContainer, View.VISIBLE);
		updateView.setImageViewUri(R.id.widgetImage, imageUri);

		Intent editWidgetIntent = new Intent(
				Intent.ACTION_EDIT, widgetUri, getApplicationContext(), AixConfigure.class);
		editWidgetIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent pendingIntent = PendingIntent.getActivity(
				getApplicationContext(), 0, editWidgetIntent, 0);
		
		updateView.setOnClickPendingIntent(R.id.widgetContainer, pendingIntent);
		
		AppWidgetManager.getInstance(getApplicationContext()).updateAppWidget(widgetId, updateView);
	}
	
	private void updateWidget(Uri widgetUri, String message, int layoutId) {
		int widgetId = (int)ContentUris.parseId(widgetUri);
		
		Intent editWidgetIntent = new Intent(
				Intent.ACTION_EDIT, widgetUri, getApplicationContext(), AixConfigure.class);
		
		editWidgetIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, editWidgetIntent, 0);
		
		RemoteViews updateView = new RemoteViews(getPackageName(), layoutId);
		updateView.setViewVisibility(R.id.widgetTextContainer, View.VISIBLE);
		updateView.setViewVisibility(R.id.widgetImageContainer, View.GONE);
		updateView.setTextViewText(R.id.widgetText, message);
		updateView.setOnClickPendingIntent(R.id.widgetContainer, pendingIntent);
		
		AppWidgetManager.getInstance(getApplicationContext()).updateAppWidget(widgetId, updateView);
	}
	
	private long calcUpdateTime(long updateTime, long newTime) {
		return newTime < updateTime ? newTime : updateTime;
	}

	public String convertStreamToString(InputStream is) throws IOException {
		if (is != null) {
			Writer writer = new StringWriter();
			char[] buffer = new char[1024];
			try {
				Reader reader = new BufferedReader(
						new InputStreamReader(is, "UTF-8"));
				int n;
				while ((n = reader.read(buffer)) != -1) {
					writer.write(buffer, 0, n);
				}
			} finally {
				is.close();
			}
			return writer.toString();
		} else {
			return "";
		}
	}
	
	private String getTimezone(ContentResolver resolver, long locationId, String latitude, String longitude) throws IOException, Exception {
		// Check if timezone info needs to be retrieved
		Cursor timezoneCursor = resolver.query(
				AixLocations.CONTENT_URI, null,
				BaseColumns._ID + '=' + Long.toString(locationId) + " AND " + AixLocationsColumns.TIME_ZONE + " IS NOT NULL",
				null, null);
		
		String timezoneId = null;
		if (timezoneCursor != null) {
			if (timezoneCursor.moveToFirst()) {
				timezoneId = timezoneCursor.getString(AixLocationsColumns.TIME_ZONE_COLUMN);
			}
			timezoneCursor.close();
		}
		
		if (TextUtils.isEmpty(timezoneId)) {
			// Don't have the timezone. Retrieve!
			String url = "http://api.geonames.org/timezoneJSON?lat=" + latitude + "&lng=" + longitude + "&username=aix_widget";
			HttpGet httpGet = new HttpGet(url);
			HttpClient httpclient = new DefaultHttpClient();
			HttpResponse response = httpclient.execute(httpGet);
			InputStream content = response.getEntity().getContent();
			
			String input = convertStreamToString(content);
			
			try {
				JSONObject jObject = new JSONObject(input);
				timezoneId = jObject.getString("timezoneId");
			} catch (JSONException e) {
				Log.d(TAG, "Failed to retrieve timezone data. " + e.getMessage());
				throw new Exception("Failed to retrieve timezone data");
			}
		}
		
		return timezoneId;
	}
	
	private void getSunTimes(ContentResolver resolver, long locationId, String latitude, String longitude) throws IOException {
		TimeZone utc = TimeZone.getTimeZone("UTC");
		Calendar utcCalendar = Calendar.getInstance(utc);
		utcCalendar.set(Calendar.HOUR_OF_DAY, 0);
		utcCalendar.set(Calendar.MINUTE, 0);
		utcCalendar.set(Calendar.SECOND, 0);
		utcCalendar.set(Calendar.MILLISECOND, 0);

		int numDaysBuffered = 5;
		
		utcCalendar.add(Calendar.DAY_OF_YEAR, -1);
		long dateFrom = utcCalendar.getTimeInMillis();
		utcCalendar.add(Calendar.DAY_OF_YEAR, numDaysBuffered - 1);
		long dateTo = utcCalendar.getTimeInMillis();
		
		int numExists = 0;
		Cursor sunCursor = null;
		
		sunCursor = resolver.query(
				AixForecasts.CONTENT_URI,
				null,
				AixForecastsColumns.LOCATION + "=" + locationId + " AND " +
				AixForecastsColumns.TIME_FROM + ">=" + dateFrom + " AND " +
				AixForecastsColumns.TIME_TO + "<=" + dateTo + " AND " +
				AixForecastsColumns.SUN_RISE + " IS NOT NULL", null, null);

		if (sunCursor != null) {
			numExists = sunCursor.getCount();
			sunCursor.close();
		}
		if (numExists >= numDaysBuffered) return;
		
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		
		dateFormat.setTimeZone(utc);
		timeFormat.setTimeZone(utc);

		String dateFromString = dateFormat.format(dateFrom);
		String dateToString = dateFormat.format(dateTo);
		
		// http://api.met.no/weatherapi/sunrise/1.0/?lat=60.10;lon=9.58;from=2009-04-01;to=2009-04-15
		HttpGet httpGet = new HttpGet(
				"http://api.met.no/weatherapi/sunrise/1.0/?lat="
				+ latitude + ";lon=" + longitude + ";from="
				+ dateFromString + ";to=" + dateToString);
		httpGet.addHeader("Accept-Encoding", "gzip");
		HttpClient httpclient = new DefaultHttpClient();
		HttpResponse response = httpclient.execute(httpGet);
		InputStream content = response.getEntity().getContent();
		
		Header contentEncoding = response.getFirstHeader("Content-Encoding");
		if (contentEncoding != null && contentEncoding.getValue().equalsIgnoreCase("gzip")) {
			content = new GZIPInputStream(content);
		}
		
		XmlPullParser parser = Xml.newPullParser();
		
		try {
			parser.setInput(content, null);
			int eventType = parser.getEventType();
			ContentValues contentValues = new ContentValues();
			
			while (eventType != XmlPullParser.END_DOCUMENT) {
				switch (eventType) {
				case XmlPullParser.END_TAG:
					if (parser.getName().equals("time") && contentValues != null) {
						resolver.insert(AixForecasts.CONTENT_URI, contentValues);
					}
					break;
				case XmlPullParser.START_TAG:
					if (parser.getName().equals("time")) {
						String date = parser.getAttributeValue(null, "date");
						try {
							long dateParsed = dateFormat.parse(date).getTime();
							contentValues.clear();
							contentValues.put(AixForecastsColumns.LOCATION, locationId);
							contentValues.put(AixForecastsColumns.TIME_FROM, dateParsed);
							contentValues.put(AixForecastsColumns.TIME_TO, dateParsed);
						} catch (Exception e) {
							contentValues = null;
						}
					} else if (parser.getName().equals("sun")) {
						if (contentValues != null) {
							try {
								long sunRise = timeFormat.parse(parser.getAttributeValue(null, "rise")).getTime();
								long sunSet = timeFormat.parse(parser.getAttributeValue(null, "set")).getTime();
								contentValues.put(AixForecastsColumns.SUN_RISE, sunRise);
								contentValues.put(AixForecastsColumns.SUN_SET, sunSet);
							} catch (Exception e) {
								contentValues = null;
							}
						}
					}
					break;
				}
				eventType = parser.next();
			}
		} catch (XmlPullParserException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void updateWeather(ContentResolver resolver, Uri viewUri)
			throws BadDataException, IOException, Exception {
		Log.d(TAG, "updateWeather started uri=" + viewUri);
		
		long locationId = -1;
		String latString = null, lonString = null;
		
		boolean found = false;
		Cursor cursor = resolver.query(Uri.withAppendedPath(viewUri, AixViews.TWIG_LOCATION), null, null, null, null);
		if (cursor != null) {
			if (cursor.moveToFirst()) {
				found = true;
				locationId = cursor.getLong(AixLocationsColumns.LOCATION_ID_COLUMN);
				latString = cursor.getString(AixLocationsColumns.LATITUDE_COLUMN);
				lonString = cursor.getString(AixLocationsColumns.LONGITUDE_COLUMN);
			}
			cursor.close();
		}
		
		if (!found) {
			throw new BadDataException();
		}
		
		float latitude, longitude;
		try {
			latitude = Float.parseFloat(latString);
			longitude = Float.parseFloat(lonString);
		} catch (NumberFormatException e) {
			Log.d(TAG, "Error parsing lat/lon! lat=" + latString + " lon=" + lonString);
			throw new BadDataException();
		}
		
		// Set a calendar up to the current time
		Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		
		calendar.add(Calendar.HOUR_OF_DAY, -48);
		resolver.delete(
				AixForecasts.CONTENT_URI,
				AixForecastsColumns.LOCATION + '=' + locationId + " AND " + AixForecastsColumns.TIME_TO + "<=" + calendar.getTimeInMillis(),
				null);
		calendar.add(Calendar.HOUR_OF_DAY, 48);
		
		String timeZoneId = null;
		
		timeZoneId = getTimezone(resolver, locationId, latString, lonString);
		
		try {
			getSunTimes(resolver, locationId, latString, lonString);
		} catch (Exception e) {
			Log.d(TAG, e.getMessage());
		}
		
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		
		// Parse weather data
		XmlPullParser parser = Xml.newPullParser();
		
		long nextUpdate = -1;
		long forecastValidTo = -1;
		
		ContentValues contentValues = new ContentValues();
		
		// Retrieve weather data
		HttpGet httpGet = new HttpGet(
				"http://api.met.no/weatherapi/locationforecast/1.8/?lat="
				+ latitude + ";lon=" + longitude);
		httpGet.addHeader("Accept-Encoding", "gzip");
		
		HttpClient httpclient = new DefaultHttpClient();
		HttpResponse response = httpclient.execute(httpGet);
		InputStream content = response.getEntity().getContent();

		Header contentEncoding = response.getFirstHeader("Content-Encoding");
		if (contentEncoding != null && contentEncoding.getValue().equalsIgnoreCase("gzip")) {
			content = new GZIPInputStream(content);
		}
		
		try {
			parser.setInput(content, null);
			int eventType = parser.getEventType();
			
			while (eventType != XmlPullParser.END_DOCUMENT) {
				switch (eventType) {
				case XmlPullParser.END_TAG:
					if (parser.getName().equals("time") && contentValues != null) {
						resolver.insert(AixForecasts.CONTENT_URI, contentValues);
					}
					break;
				case XmlPullParser.START_TAG:
					if (parser.getName().equals("time")) {
						String fromString = parser.getAttributeValue(null, "from");
						String toString = parser.getAttributeValue(null, "to");
						
						try {
    						long from = dateFormat.parse(fromString).getTime();
							long to = dateFormat.parse(toString).getTime();
							contentValues.clear();
    						contentValues.put(AixForecastsColumns.LOCATION, locationId);
    						contentValues.put(AixForecastsColumns.TIME_FROM, from);
    						contentValues.put(AixForecastsColumns.TIME_TO, to);
						} catch (Exception e) {
							Log.d(TAG, "Error parsing from & to values. from="
									+ fromString + " to=" + toString);
							contentValues = null;
						}
					} else if (parser.getName().equals("temperature")) {
						if (contentValues != null) {
							contentValues.put(AixForecastsColumns.TEMPERATURE,
									Float.parseFloat(parser.getAttributeValue(null, "value")));
						}
					} else if (parser.getName().equals("humidity")) {
						if (contentValues != null) {
							contentValues.put(AixForecastsColumns.HUMIDITY,
									Float.parseFloat(parser.getAttributeValue(null, "value")));
						}
					} else if (parser.getName().equals("pressure")) {
						if (contentValues != null) {
							contentValues.put(AixForecastsColumns.PRESSURE,
									Float.parseFloat(parser.getAttributeValue(null, "value")));
						}
					} else if (parser.getName().equals("symbol")) {
						if (contentValues != null) {
							contentValues.put(AixForecastsColumns.WEATHER_ICON,
									Integer.parseInt(parser.getAttributeValue(null, "number")));
						}
					} else if (parser.getName().equals("precipitation")) {
						if (contentValues != null) {
							contentValues.put(AixForecastsColumns.RAIN_VALUE,
									Float.parseFloat(
											parser.getAttributeValue(null, "value")));
							try {
								contentValues.put(AixForecastsColumns.RAIN_LOWVAL,
    									Float.parseFloat(
    											parser.getAttributeValue(null, "minvalue")));
							} catch (Exception e) {
								/* LOW VALUE IS OPTIONAL */
							}
							try {
    							contentValues.put(AixForecastsColumns.RAIN_HIGHVAL,
    									Float.parseFloat(
    											parser.getAttributeValue(null, "maxvalue")));
							} catch (Exception e) {
								/* HIGH VALUE IS OPTIONAL */
							}
						}
					} else if (parser.getName().equals("model")) {
						String model = parser.getAttributeValue(null, "name");
						if (model.toLowerCase().equals("yr")) {
							try {
								nextUpdate = dateFormat.parse(parser.getAttributeValue(null, "nextrun")).getTime();
							} catch (ParseException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							try {
								forecastValidTo = dateFormat.parse(parser.getAttributeValue(null, "to")).getTime();
							} catch (ParseException e) {
								e.printStackTrace();
							}
						}
					}
					break;
				}
				eventType = parser.next();
			}
			
			// Successfully retrieved weather data. Update lastUpdated parameter
			contentValues.clear();
			if (timeZoneId != null) {
				contentValues.put(AixLocationsColumns.TIME_ZONE, timeZoneId);
			}
			contentValues.put(AixLocationsColumns.LAST_FORECAST_UPDATE, calendar.getTimeInMillis());
			contentValues.put(AixLocationsColumns.FORECAST_VALID_TO, forecastValidTo);
			contentValues.put(AixLocationsColumns.NEXT_FORECAST_UPDATE, nextUpdate);
			resolver.update(Uri.withAppendedPath(viewUri, AixViews.TWIG_LOCATION), contentValues, null, null);
		} catch (XmlPullParserException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
