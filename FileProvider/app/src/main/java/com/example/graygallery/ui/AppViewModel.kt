/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.graygallery.ui

import android.app.Application
import android.content.Context
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.graygallery.R
import com.example.graygallery.utils.Source
import com.example.graygallery.utils.applyGrayscaleFilter
import com.example.graygallery.utils.copyImageFromStream
import com.example.graygallery.utils.generateFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val FILEPATH_XML_KEY = "files-path"
private const val RANDOM_IMAGE_URL = "https://source.unsplash.com/random/500x500"
val ACCEPTED_MIMETYPES = arrayOf("image/jpeg", "image/png")
val RAR_MIMETYPES = arrayOf("application/x-rar-compressed", "application/octet-stream")
val ZIP_MIMETYPES = arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "multipart/x-zip")
private const val TAG = "msg"

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val httpClient by lazy { OkHttpClient() }

    private val context: Context
        get() = getApplication()

    private val _notification = MutableLiveData<String>()
    val notification: LiveData<String>
        get() = _notification

    private val imagesFolder: File by lazy { getImagesFolder(context) }

    private val _images = MutableLiveData(emptyList<File>())
    val images: LiveData<List<File>>
        get() = _images

    fun loadImages() {
        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) {
                imagesFolder.listFiles().toList()
            }

            _images.postValue(images)
        }
    }

    fun saveImageFromCamera(bitmap: Bitmap) {
        val imageFile = File(imagesFolder, generateFilename(Source.CAMERA))
        val imageStream = FileOutputStream(imageFile)

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val grayscaleBitmap = withContext(Dispatchers.Default) {
                        applyGrayscaleFilter(bitmap)
                    }
                    grayscaleBitmap.compress(Bitmap.CompressFormat.JPEG, 100, imageStream)
                    imageStream.flush()
                    imageStream.close()

                    _notification.postValue("Camera image saved")

                } catch (e: Exception) {
                    Log.e(javaClass.simpleName, "Error writing bitmap", e)
                }
            }
        }
    }

    fun copyImageFromUri(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.let {
                    // TODO: Apply grayscale filter before saving image
                    copyImageFromStream(it, imagesFolder)
                    _notification.postValue("Image copied")
                }
            }
        }
    }


    /**
     * Unzip speed is low. Its ok to use unzipping document files
     */
    fun unzipDocumentFiles(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "IO ${Thread.currentThread().name}")
                context.contentResolver.openInputStream(uri)?.let {

                    try {
                        val zin = ZipInputStream(it)
                        var ze: ZipEntry? = null

                        while (zin.nextEntry.also { ze = it } != null) {
                            Log.d(TAG, "unzip(): Unzipping file path: ${ze?.name}")

                            /** Create directory if required while unzipping */
                            createDir(ze?.name ?: "")

                            if (ze?.isDirectory == true) {
                                Log.d(TAG, "Create directory if required while unzipping")
                                val file = File("${context.filesDir}/${ze?.name}")

                                Log.d(TAG, "Is directory created at Path: ${file.absolutePath} : ${file.mkdir()}")

                            } else {
                                /** Create file while unzipping */
                                val file = File("${context.filesDir}/${ze?.name}")

                                Log.d(TAG, "Is file created at path : ${file.absolutePath} : ${file.createNewFile()}")

                                val fout = FileOutputStream(file)
                                var c = zin.read()
                                while (c != -1) {
                                    fout.write(c)
                                    c = zin.read()
                                }
                                zin.closeEntry()
                                fout.close()
                            }
                        }
                        zin.close()

                        Log.d(TAG, "Unzip Successful!!!")

                    } catch (e: IOException) {
                        Log.e(TAG, "IOException: ${e.message}")
                    }
                }
            }
        }
    }


    /**
     * Faster in unzipping large media files
     */
    fun unzipMediaFiles(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "IO ${Thread.currentThread().name}")
                context.contentResolver.openInputStream(uri)?.let {

                    try {
                        val zipInputStream = ZipInputStream(it)
                        val bufferedInputStream = BufferedInputStream(zipInputStream)
                        var zipEntry: ZipEntry? = null

                        while (zipInputStream.nextEntry.also { zipEntry = it } != null) {
                            Log.d(TAG, "unzip(): Unzipping file path: ${zipEntry?.name}")

                            /** Create directory if required while unzipping */
                            createDir(zipEntry?.name ?: "")

                            if (zipEntry?.isDirectory == true) {
                                Log.d(TAG, "Create directory if required while unzipping")
                                val file = File("${context.cacheDir}/${zipEntry?.name}")

                                Log.d(TAG, "Is directory created at Path: ${file.absolutePath} : ${file.mkdir()}")

                            } else {
                                /** Create file while unzipping */
                                val file = File("${context.cacheDir}/${zipEntry?.name}")

                                Log.d(TAG, "Is file created at path : ${file.absolutePath} : ${file.createNewFile()}")

                                val bufferedOutputStream = BufferedOutputStream(FileOutputStream(file))
                                val buffer = ByteArray(1024)
                                var read = 0
                                while (bufferedInputStream.read(buffer).also { read = it } != -1) {
                                    bufferedOutputStream.write(buffer, 0, read)
                                }
                                zipInputStream.closeEntry()
                                bufferedOutputStream.close()
                            }
                        }
                        zipInputStream.close()

                        Log.d(TAG, "Unzip Successful!!!")

                    } catch (e: IOException) {
                        Log.e(TAG, "IOException: ${e.message}")
                    }
                }
            }
        }
    }

    private fun createDir(path: String) {
        var file = File("${context.filesDir}")

        var folderList = path.split(File.separator)
        folderList = folderList.subList(0, folderList.size - 1)

        folderList.forEach {
            file = File("${file.path}/$it")
            Log.d(TAG, "createDir(): Is directory created at Path: ${file.absolutePath} : ${file.mkdir()}")
        }
    }

    fun saveRandomImageFromInternet() {
        viewModelScope.launch {
            val request = Request.Builder().url(RANDOM_IMAGE_URL).build()

            withContext(Dispatchers.IO) {
                val response = httpClient.newCall(request).execute()

                response.body?.let { responseBody ->
                    val imageFile = File(imagesFolder, generateFilename(Source.INTERNET))
                    // TODO: Apply grayscale filter before saving image
                    imageFile.writeBytes(responseBody.bytes())
                    _notification.postValue("Image downloaded")
                }

                if (!response.isSuccessful) {
                    _notification.postValue("Failed to download image")
                }
            }
        }
    }

    fun deleteCache(context: Context) {
        try {
            val dir = context.cacheDir
            deleteDir(dir)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    fun deleteDir(dir: File?): Boolean {
        return if (dir != null && dir.isDirectory) {
            val children = dir.list()
            for (i in children.indices) {
                val success = deleteDir(File(dir, children[i]))
                if (!success) {
                    return false
                }
            }
            dir.delete()
        } else if (dir != null && dir.isFile) {
            dir.delete()
        } else {
            false
        }
    }

    fun clearFiles() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imagesFolder.deleteRecursively()
                _images.postValue(emptyList())
                _notification.postValue("Images cleared")
            }
        }
    }
}

private fun getImagesFolder(context: Context): File {
    val xml = context.resources.getXml(R.xml.filepaths)

    val attributes = getAttributesFromXmlNode(xml, FILEPATH_XML_KEY)

    val folderPath = attributes["path"] ?: error("You have to specify the sharable directory in res/xml/filepaths.xml")

    return File(context.filesDir, folderPath).also {
        if (!it.exists()) {
            it.mkdir()
        }
    }
}

// TODO: Make the function suspend
private fun getAttributesFromXmlNode(xml: XmlResourceParser, nodeName: String): Map<String, String> {
    while (xml.eventType != XmlResourceParser.END_DOCUMENT) {
        if (xml.eventType == XmlResourceParser.START_TAG) {
            if (xml.name == nodeName) {
                if (xml.attributeCount == 0) {
                    return emptyMap()
                }

                val attributes = mutableMapOf<String, String>()

                for (index in 0 until xml.attributeCount) {
                    attributes[xml.getAttributeName(index)] = xml.getAttributeValue(index)
                }

                return attributes
            }
        }

        xml.next()
    }

    return emptyMap()
}
