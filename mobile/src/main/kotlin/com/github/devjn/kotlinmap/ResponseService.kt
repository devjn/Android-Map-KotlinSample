package com.github.devjn.kotlinmap

import android.util.Log
import com.github.devjn.kotlinmap.LocationService.Companion.retrofit
import com.github.devjn.kotlinmap.utils.Helper
import com.github.devjn.kotlinmap.utils.PlacePoint
import com.github.devjn.kotlinmap.utils.ServerRespose
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.javadocmd.simplelatlng.util.LengthUnit
import org.ferriludium.simplegeoprox.FeSimpleGeoProx
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import rx.functions.Action0
import rx.schedulers.Schedulers
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Created by Jahongir on 06-Nov-16.
 */

class ResponseService {

    interface LocationResultListener {
        fun onLocationResult(result: Collection<PlaceClusterItem>?)
    }

    private var mPlacesVersion: Int = 0
    private var locationService: LocationService? = null
    private var mListener: LocationResultListener? = null

    constructor() {
        mPlacesVersion = Common.placesVersion
        locationService = retrofit.create(LocationService::class.java)
        checkMapObjects()
    }

    fun setListener(listener: LocationResultListener) {
        this.mListener = listener
    }

    private fun checkMapObjects() {
        val call = locationService!!.version
        call.enqueue(versionCallback)
    }

    private val getAllCallback = object : Callback<ServerRespose.MapAll> {
        override fun onResponse(call: Call<ServerRespose.MapAll>, response: Response<ServerRespose.MapAll>) {
            val mapAll = response.body()
            val result = mapAll.placePoints
            if (result is List<*>) {
                mapObjects = result as List<PlaceClusterItem>
//                world = FeSimpleGeoProx(mapObjects)
                Log.i(TAG, "getAllCallback response: " + result)
                val gson = GsonBuilder().create()
                val file = gson.toJson(mapObjects)
                Helper.writeAsync(Common.MAP_FILENAME, file)
                Common.placesVersion = mapAll.version
                mPlacesVersion = mapAll.version
            }
        }

        override fun onFailure(call: Call<ServerRespose.MapAll>, t: Throwable) {
            Log.e(TAG, "getAllCallback response failed: " + t)
        }
    }

    private val versionCallback = object : Callback<Int> {
        override fun onResponse(call: Call<Int>, response: Response<Int>) {
            val version = response.body()
            Log.i(TAG, "versionCallback response: " + version!!)
            if (version > mPlacesVersion) {
                requestNewVersion()
            } else
                checkLocal()
        }

        override fun onFailure(call: Call<Int>, t: Throwable) {
            Log.e(TAG, "versionCallback response failed: " + t)
            checkLocal()
        }
    }

    private val nearCallback = object : Callback<Collection<PlaceClusterItem>> {
        override fun onResponse(call: Call<Collection<PlaceClusterItem>>, response: Response<Collection<PlaceClusterItem>>) {
            val result = response.body()
            mListener!!.onLocationResult(result)
        }
        override fun onFailure(call: Call<Collection<PlaceClusterItem>>, t: Throwable) {}
    }

    private fun requestNewVersion() {
        val call = locationService!!.all
        call.enqueue(getAllCallback)
    }

    private fun checkLocal() {
        Schedulers.io().createWorker().schedule(Action0 {
            val file = File(Common.applicationContext.filesDir.path + File.separator + Common.MAP_FILENAME)
            if (file.exists()) {
                try {
                    val gson = Gson()
                    val jsonFile = read(Common.MAP_FILENAME)
                    val listType = object : TypeToken<List<PlaceClusterItem>>() {}.type
                    mapObjects = gson.fromJson<List<PlaceClusterItem>>(jsonFile, listType)
                    world = FeSimpleGeoProx(mapObjects)
                    mListener?.onLocationResult(mapObjects)
                    Log.i(TAG, "world created, size= " + mapObjects!!.size + " content: " + mapObjects)
                    return@Action0
                } catch (e: IOException) {
                    Log.e(TAG, "Read exception: " + e)
                } catch (e: ClassCastException) {
                    Log.wtf(TAG, "mapObjects is not of needed type, exception: " + e)
                }
            } else {
                Log.e(TAG, "Map file doesn't exist")
                Schedulers.io().createWorker().schedule(Action0 {
                    mapObjects = GeoJsonConverter.ConvertLocalJson();
                    world = FeSimpleGeoProx(mapObjects)
                    mListener?.onLocationResult(mapObjects)
                    Log.i(TAG, "world created, size= " + mapObjects!!.size + " content: " + mapObjects)
                    return@Action0
                })
            }
            requestNewVersion()
        })
    }

    fun getNearLocations(lat: Double, lng: Double) {
        if (world != null) {
            val result = world!!.find(LatLng(lat, lng), 1.0, LengthUnit.KILOMETER)
            mListener!!.onLocationResult(result as Collection<PlaceClusterItem>?)
        } else {
            val call = locationService!!.nearLocations(lat, lng)
            call.enqueue(nearCallback)
        }
    }

    fun getNearLocations(lat: Double, lng: Double, listener: LocationResultListener) {
        if (world != null) {
            val result = world!!.find(LatLng(lat, lng), 1.0, LengthUnit.KILOMETER)
            listener.onLocationResult(result as Collection<PlaceClusterItem>?)
        } else {
            val call = locationService!!.nearLocations(lat, lng)
            call.enqueue(object : Callback<Collection<PlaceClusterItem>> {
                override fun onResponse(call: Call<Collection<PlaceClusterItem>>, response: Response<Collection<PlaceClusterItem>>) {
                    val result = response.body()
                    listener.onLocationResult(result)
                }
                override fun onFailure(call: Call<Collection<PlaceClusterItem>>, t: Throwable) {
                }
            })
        }
    }

    val allLocations: List<PlaceClusterItem>
        get() = mapObjects!!

    private object Holder { val INSTANCE = ResponseService() }



    companion object {

        private val TAG = ResponseService::class.java.simpleName

        private var world: FeSimpleGeoProx<PlacePoint>? = null
        protected var mapObjects: List<PlaceClusterItem>? = null

        val instance: ResponseService by lazy { Holder.INSTANCE }

        //--------Functions---------

        @Throws(IOException::class)
        private fun read(filename: String): String {
            val fis = Common.applicationContext.openFileInput(filename)
            val isr = InputStreamReader(fis)
            val bufferedReader = BufferedReader(isr)
            val sb = StringBuilder()

            bufferedReader.readWhile { it != 1 }.forEach {
                sb.append(it)
            }
            bufferedReader.close()
            isr.close()
            fis.close()
            return sb.toString()
        }
    }

}

inline fun BufferedReader.readWhile(crossinline predicate: (Int) -> Boolean): Sequence<Char> {
    return generateSequence {
        val c = this.read()
        if (c != -1 && predicate(c)) {
            c.toChar()
        } else {
            null
        }
    }
}