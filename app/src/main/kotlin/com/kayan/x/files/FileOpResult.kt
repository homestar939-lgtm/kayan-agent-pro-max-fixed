package com.kayan.x.files

data class FileOpResult(
    val success: Boolean,
    val data: Any? = null,       // String, List<String>, Map<String, Any>, etc.
    val error: String? = null
) {
    companion object {
        fun success(data: Any) = FileOpResult(success = true, data = data)
        fun error(msg: String) = FileOpResult(success = false, error = msg)
    }
}
