package com.nick.easyhttp.core

import com.nick.easyhttp.HttpResult
import com.nick.easyhttp.enums.HttpProtocol
import com.nick.easyhttp.enums.ReqMethod
import com.nick.easyhttp.internal.HttpConfigFactory
import com.nick.easyhttp.internal.HttpService
import com.nick.easyhttp.util.parseAsList
import com.nick.easyhttp.util.parseAsObject
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Call
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.*

class HttpRequest internal constructor(private val reqUrl: String, private val reqMethod: ReqMethod) : CoroutineScope by MainScope() {

	private var reqTag: Any? = null

	private var queryMap = hashMapOf<String, String>()

	private var headerMap = hashMapOf<String, String>()

	private var fieldMap = hashMapOf<String, String>()

	private val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)

	private var isMultiPart = false

	private var jsonString = ""

	private lateinit var call: Call<String>

	private val retrofit by lazy {
		HttpConfigFactory.retrofit
			?: throw RuntimeException("please config EasyHttp first!!!")
	}

	fun addQuery(key: String, value: String): HttpRequest {
		queryMap[key] = value
		return this
	}

	fun addQueries(queryMap: HashMap<String, String>): HttpRequest {
		if (queryMap.isNotEmpty()) {
			this.queryMap.forEach {
				if (!this.queryMap.containsKey(it.key)) {
					this.queryMap[it.key] = it.value
				}
			}
		}
		return this
	}

	fun addField(key: String, value: String): HttpRequest {
		isMultiPart = false
		fieldMap[key] = value
		return this
	}

	fun addFields(fieldMap: HashMap<String, String>): HttpRequest {
		if (fieldMap.isNotEmpty()) {
			fieldMap.forEach {
				if (!this.fieldMap.containsKey(it.key)) {
					addField(it.key, it.value)
				}
			}
		}
		return this
	}

	fun addJsonString(jsonString: String): HttpRequest {
		this.jsonString = jsonString
		return this
	}

	fun addMultiPart(key: String, value: Any): HttpRequest {
		isMultiPart = true
		if (value is String) {
			multipartBody.addFormDataPart(key, value)
		} else if (value is File) {
			val requestBody = value.asRequestBody("Content-Type: application/octet-stream".toMediaTypeOrNull())
			multipartBody.addFormDataPart(key, value.name, requestBody)
		}
		return this
	}

	fun addHeader(key: String, value: String): HttpRequest {
		headerMap[key] = value
		return this
	}

	fun addHeaders(headerMap: HashMap<String, String>): HttpRequest {
		if (headerMap.isNotEmpty()) {
			headerMap.forEach {
				if (!this.headerMap.containsKey(it.key)) {
					addHeader(it.key, it.value)
				}
			}
		}
		return this
	}

	fun tag(reqTag: Any): HttpRequest {
		this.reqTag = reqTag
		return this
	}

	fun isMultiPart(multiPart: Boolean): HttpRequest {
		this.isMultiPart = multiPart
		return this
	}

	private fun constructFormBody(): FormBody {
		val formBodyBuilder = FormBody.Builder()
		if (!fieldMap.isNullOrEmpty()) {
			fieldMap.forEach {
				formBodyBuilder.addEncoded(it.key, it.value)
			}
		}
		return formBodyBuilder.build()
	}

	private fun request(): Call<String> {
		val realHttpUrl = reqUrl.startsWith(HttpProtocol.HTTP.schema, true) or reqUrl.startsWith(HttpProtocol.HTTPS.schema, true)
		val url = if (realHttpUrl) reqUrl else retrofit.baseUrl().toString().plus(reqUrl)
		val httpProcessorService = retrofit.create(HttpService::class.java)
		val requestBody = jsonString.toRequestBody("Content-Type:application/json;charset=utf-8".toMediaTypeOrNull())
		call = when (reqMethod) {
			ReqMethod.GET -> httpProcessorService.get(url, headerMap, queryMap, reqTag)
			ReqMethod.POST -> httpProcessorService.post(url, headerMap, queryMap, requestBody, reqTag)
			ReqMethod.GET_FORM -> httpProcessorService.getForm(url, headerMap, queryMap, fieldMap, reqTag)
			ReqMethod.POST_FORM -> httpProcessorService.post(url, headerMap, queryMap,
				if (isMultiPart) multipartBody.build() else constructFormBody(), reqTag)
			ReqMethod.PUT -> httpProcessorService.put(url, headerMap, queryMap, requestBody, reqTag)
			ReqMethod.DELETE -> httpProcessorService.delete(url, headerMap, queryMap, requestBody, reqTag)
			ReqMethod.PUT_FORM -> httpProcessorService.put(url, headerMap, queryMap,
				if (isMultiPart) multipartBody.build() else constructFormBody(), reqTag)
			ReqMethod.DELETE_FORM -> httpProcessorService.delete(url, headerMap, queryMap,
				if (isMultiPart) multipartBody.build() else constructFormBody(), reqTag)
		}
		return call
	}

	suspend fun execute(): Response<String> {
		return withContext(Dispatchers.IO) {
			request().execute()
		}
	}

	suspend fun asString(): HttpResult<String> {
		return asString(HttpConfigFactory.defaultResStringHandler)
	}

	suspend fun asString(handler: HttpResult.ResStringHandler): HttpResult<String> {
		return withContext(Dispatchers.IO) {
			try {
				val response = request().execute()
				val code = response.code()
				val headers = response.headers()
				if (response.isSuccessful) {
					val responseBody = response.body() as String
					HttpResult.success(handler.onHandle(responseBody), code, headers)
				} else {
					val result = response.errorBody()?.string() as String
					HttpResult.error(result, code, headers)
				}
			} catch (e: IOException) {
				HttpResult.throwable<String>(e)
			}
		}
	}

	suspend fun <T> asList(clazz: Class<T>): HttpResult<MutableList<T>> {
		return asList(clazz, HttpConfigFactory.defaultResStringHandler)
	}

	suspend fun <T> asList(clazz: Class<T>, handler: HttpResult.ResStringHandler): HttpResult<MutableList<T>> {
		return withContext(Dispatchers.IO) {
			try {
				val response = request().execute()
				val code = response.code()
				val headers = response.headers()
				if (response.isSuccessful) {
					val responseBody = response.body() as String
					HttpResult.success(handler.onHandle(responseBody).parseAsList(clazz), code, headers)
				} else {
					val result = response.errorBody()?.string() as String
					HttpResult.error(result, code, headers)
				}
			} catch (e: IOException) {
				HttpResult.throwable<MutableList<T>>(e)
			}
		}
	}

	suspend fun <T> asObject(clazz: Class<T>): HttpResult<T> {
		return asObject(clazz, HttpConfigFactory.defaultResStringHandler)
	}

	suspend fun <T> asObject(clazz: Class<T>, handler: HttpResult.ResStringHandler): HttpResult<T> {
		return withContext(Dispatchers.IO) {
			try {
				val response = request().execute()
				val code = response.code()
				val headers = response.headers()
				if (response.isSuccessful) {
					val responseBody = response.body() as String
					HttpResult.success(handler.onHandle(responseBody).parseAsObject(clazz), code, headers)
				} else {
					val result = response.errorBody()?.string() as String
					HttpResult.error(result, code, headers)
				}
			} catch (e: IOException) {
				HttpResult.throwable<T>(e)
			}
		}
	}

	fun cancelRequest() {
		if (call.isExecuted && !call.isCanceled) {
			call.cancel()
		}
		cancel()
	}
}