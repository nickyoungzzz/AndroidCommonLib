package com.nick.easyhttp.core.download

import java.io.InputStream

interface DownloadHandler {

	fun saveFile(inputStream: InputStream, downParam: DownParam,
	             contentLength: Long, listener: (state: DownState) -> Unit
	)

	fun cancel()

	companion object {
		@JvmField
		val OKIO_DOWNLOAD_HANDLER = OkIoDownHandler()
	}
}