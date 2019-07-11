package com.mapbox.services.android.navigation.v5.navigation;

import android.location.Location;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.geojson.Geometry;
import com.mapbox.geojson.LineString;
import com.mapbox.geojson.Point;
import com.mapbox.geojson.gson.GeometryGeoJson;
import com.mapbox.navigator.BannerInstruction;
import com.mapbox.navigator.FixLocation;
import com.mapbox.navigator.NavigationStatus;
import com.mapbox.navigator.Navigator;
import com.mapbox.navigator.VoiceInstruction;

import java.util.ArrayList;
import java.util.Date;

class MapboxNavigator {

  private static final int INDEX_FIRST_ROUTE = 0;
  private static final float GRID_SIZE = 0.0025f;
  private static final short BUFFER_DILATION = 1;
  private final Navigator navigator;
  private final RouteHandler routeHandler;

  MapboxNavigator(Navigator navigator) {
    this.navigator = navigator;
    this.routeHandler = new RouteHandler(this);
  }

  void updateRoute(DirectionsRoute route, DirectionsRouteType routeType) {
    routeHandler.updateRoute(route, routeType);
  }

  synchronized NavigationStatus setRoute(@NonNull String routeJson, int routeIndex, int legIndex) {
    return navigator.setRoute(routeJson, routeIndex, legIndex);
  }

  synchronized boolean updateAnnotations(@NonNull String legAnnotationJson, int routeIndex, int legIndex) {
    return navigator.updateAnnotations(legAnnotationJson, routeIndex, legIndex);
  }

  synchronized NavigationStatus retrieveStatus(Date date, long lagInMilliseconds) {
    // We ask for a point slightly in the future to account for lag in location services
    if (lagInMilliseconds > 0) {
      date.setTime(date.getTime() + lagInMilliseconds);
    }
    return navigator.getStatus(date);
  }

  void updateLocation(Location raw) {
    FixLocation fixedLocation = buildFixLocationFromLocation(raw);
    synchronized (this) {
      navigator.updateLocation(fixedLocation);
    }
  }

  Location getSnappedLocation(NavigationStatus status, Location fallbackLocation) {
    Location snappedLocation = new Location(fallbackLocation);
    snappedLocation.setProvider("map_matched");
    FixLocation fixLocation = status.getLocation();
    Point coordinate = fixLocation.getCoordinate();
    snappedLocation.setLatitude(coordinate.latitude());
    snappedLocation.setLongitude(coordinate.longitude());
    if (fixLocation.getBearing() != null) {
      snappedLocation.setBearing(fixLocation.getBearing());
    }
    snappedLocation.setTime(fixLocation.getTime().getTime());
    return snappedLocation;
  }

  synchronized NavigationStatus updateLegIndex(int index) {
    return navigator.changeRouteLeg(INDEX_FIRST_ROUTE, index);
  }

  /**
   * Gets the history of state changing calls to the navigator this can be used to
   * replay a sequence of events for the purpose of bug fixing.
   *
   * @return a json representing the series of events that happened since the last time
   * history was toggled on
   */
  synchronized String retrieveHistory() {
    return navigator.getHistory();
  }

  /**
   * Toggles the recording of history on or off.
   *
   * @param isEnabled set this to true to turn on history recording and false to turn it off
   *                  toggling will reset all history call getHistory first before toggling
   *                  to retain a copy
   */
  synchronized void toggleHistory(boolean isEnabled) {
    navigator.toggleHistory(isEnabled);
  }

  synchronized void addHistoryEvent(String eventType, String eventJsonProperties) {
    navigator.pushHistory(eventType, eventJsonProperties);
  }

  synchronized VoiceInstruction retrieveVoiceInstruction(int index) {
    return navigator.getVoiceInstruction(index);
  }

  synchronized BannerInstruction retrieveBannerInstruction(int index) {
    return navigator.getBannerInstruction(index);
  }

  @Nullable
  synchronized Geometry retrieveRouteGeometry() {
    ArrayList<Point> routeGeometry = navigator.getRouteGeometry();
    if (routeGeometry == null) {
      return null;
    }
    return LineString.fromLngLats(routeGeometry);
  }

  @Nullable
  synchronized Geometry retrieveRouteGeometryWithBuffer() {
    String routeGeometryWithBuffer = navigator.getRouteBufferGeoJson(GRID_SIZE, BUFFER_DILATION);
    if (routeGeometryWithBuffer == null) {
      return null;
    }
    return GeometryGeoJson.fromJson(routeGeometryWithBuffer);
  }

  private FixLocation buildFixLocationFromLocation(Location location) {
    Date time = new Date();
    Point rawPoint = Point.fromLngLat(location.getLongitude(), location.getLatitude());
    Float speed = checkFor(location.getSpeed());
    Float bearing = checkFor(location.getBearing());
    Float altitude = checkFor((float) location.getAltitude());
    Float horizontalAccuracy = checkFor(location.getAccuracy());
    String provider = location.getProvider();

    return new FixLocation(
      rawPoint,
      time,
      speed,
      bearing,
      altitude,
      horizontalAccuracy,
      provider
    );
  }

  private Float checkFor(Float value) {
    if (value == 0.0) {
      return null;
    }
    return value;
  }
}
