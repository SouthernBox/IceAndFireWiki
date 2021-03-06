package com.southernbox.infwiki.ui

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.southernbox.infwiki.R
import com.southernbox.infwiki.entity.Page
import com.southernbox.infwiki.entity.WebData
import com.southernbox.infwiki.util.RequestUtil
import kotlinx.android.synthetic.main.activity_detail.*
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.URLDecoder


/**
 * Created SouthernBox on 2016/3/27.
 * 详情页面
 */

@Suppress("NAME_SHADOWING")
class DetailActivity : BaseActivity() {

    private var webList = ArrayList<WebData>()
    private lateinit var title: String
    var isGetContent = false
    var isGetImage = false

    private lateinit var locationManager: LocationManager

    companion object {

        fun show(context: Context, title: String?) {
            val intent = Intent(context, DetailActivity::class.java)
            val bundle = Bundle()
            bundle.putString("title", title)
            intent.putExtras(bundle)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        val bundle = intent.extras
        title = bundle.getString("title")
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        initView()
        getContent()
    }

    private fun initView() {
        initTheme()
        initToolbar()
        initWebView()
    }

    private fun initTheme() {
        val theme = mContext.theme
        val lightTextColor = TypedValue()
        theme.resolveAttribute(R.attr.lightTextColor, lightTextColor, true)
    }

    private fun initToolbar() {
        setSupportActionBar(detail_toolbar)
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
        detail_toolbar.setNavigationOnClickListener({
            web_view.visibility = View.GONE
            onBackPressed()
        })
        detail_toolbar.post({
            //设置Toolbar的图标颜色
            val navigationIcon = detail_toolbar.navigationIcon
            if (navigationIcon != null) {
                if (mDayNightHelper.isDay) {
                    navigationIcon.alpha = 255
                } else {
                    navigationIcon.alpha = 128
                }
            }
        })
    }

    private fun initWebView() {
        // 开启 DOM storage API 功能
        web_view.settings.domStorageEnabled = true
        // 开启 Application Caches 功能
        web_view.settings.setAppCacheEnabled(true)
        // 支持缩放
//        web_view.settings.setSupportZoom(true)
//        web_view.settings.builtInZoomControls = true
//        web_view.settings.displayZoomControls = false
        // 重定向
        web_view.webViewClient = MyWebViewClient()
    }

    private fun getContent() {
        isGetContent = true
        progress_bar.visibility = View.VISIBLE
        //读取缓存
        val cachePage = mRealm.where(Page::class.java)
                .equalTo("title", title)
                .findFirst()
        if (cachePage != null && cachePage.content.isNotEmpty()) {
            showNextContentPage(cachePage.content)
        }

        val call = RequestUtil.wikiRequestServes.getContent(title)
        call.enqueue(object : Callback<String> {

            override fun onResponse(call: Call<String>?, response: Response<String>) {
                try {
                    val jsonObject = JSONObject(response.body())
                    val pageObject = jsonObject.optJSONObject("query").optJSONObject("pages")
                    val keys = pageObject.keys()
                    if (keys.hasNext()) {
                        val key = keys.next()
                        val page = pageObject.optJSONObject(key)
                        val pageid = pageObject.optInt("pageid")
                        val content = page.optJSONArray("revisions").optJSONObject(0).optString("*")
                        //保存到数据库
                        mRealm.beginTransaction()
                        if (cachePage != null) {
                            mRealm.copyFromRealm(cachePage)
                            cachePage.content = content
                        } else {
                            val newPage = Page()
                            newPage.pageid = pageid
                            newPage.title = title
                            newPage.content = content
                            mRealm.copyToRealmOrUpdate(newPage)
                        }
                        mRealm.commitTransaction()
                        showNextContentPage(content)
                    } else {
                        progress_bar.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e("解析页面信息错误", e.toString())
                    Log.e("getContentResponse", response.body())
                    netError()
                }
            }

            override fun onFailure(call: Call<String>?, t: Throwable?) {
                netError()
            }
        })
    }

    private fun showNextContentPage(content: String) {
        if (webList.size > 0) {
            //保存当前页面的滚动位置
            val webData = webList[webList.lastIndex]
            webData.scrollY = web_view.scrollY
            //检查是否已经读了缓存数据
            if (webData.title == title) {
                //检查和缓存数据是否相同
                if (webData.content != content) {
                    webData.content = content
                    showPage()
                }
                return
            }
        }
        webList.add(WebData(title, content, 0, WebData.Type.HTML))
        showPage()
    }

    private fun getImage() {
        isGetImage = true
        progress_bar.visibility = View.VISIBLE
        val call = RequestUtil.wikiRequestServes.getImage(title)
        call.enqueue(object : Callback<String> {

            override fun onResponse(call: Call<String>?, response: Response<String>) {
                try {
                    val jsonObject = JSONObject(response.body())
                    val queryObject = jsonObject.optJSONObject("query") ?: return
                    val pageObject = queryObject.optJSONObject("pages") ?: return
                    val keys = pageObject.keys()
                    if (keys.hasNext()) {
                        val key = keys.next()
                        val page = pageObject.optJSONObject(key)
                        val imgUrl = page.optJSONObject("thumbnail").optString("source")
                        if (webList.size > 0) {
                            //保存当前页面的滚动位置
                            val webData = webList[webList.lastIndex]
                            webData.scrollY = web_view.scrollY
                        }
                        webList.add(WebData("图片", imgUrl, 0, WebData.Type.URL))
                        showPage()
                    } else {
                        progress_bar.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e("解析图片信息错误", e.toString())
                    Log.e("getImageResponse", response.body())
                    netError()
                }
            }

            override fun onFailure(call: Call<String>?, t: Throwable?) {
                netError()
            }
        })
    }

    private fun showPage() {
        if (webList.size <= 0) {
            return
        }
        val webData = webList[webList.lastIndex]
        if (webData.type == WebData.Type.URL) {
            web_view.loadUrl(webData.content)
        } else {
            var htmlData = webData.content
            if (mDayNightHelper.isNight) {
                htmlData = "<head>" +
                        "<style type=\"text/css\">" +
                        "a{color:#607d8b;}" + //超链接颜色
                        "</style>" +
                        "</head>" +
                        "<body bgcolor=\"#4F4F4F\">" + //背景颜色
                        "<font color=\"#9F9F9F\">" + //字体颜色
                        htmlData +
                        "</font>" +
                        "</body>"
            } else {
                htmlData = "<head>" +
                        "<style type=\"text/css\">" +
                        "a{color:#607d8b;}" + //超链接颜色
                        "</style>" +
                        "</head>" +
                        htmlData
            }
            web_view.loadDataWithBaseURL(
                    "file:///android_asset/",
                    htmlData,
                    "text/html",
                    "utf-8",
                    null)
        }
        detail_toolbar.post({ detail_toolbar.title = webData.title })
    }

    private fun netError() {
        progress_bar.visibility = View.GONE
        //如果已经展示了缓存数据，不用再显示错误页面
        if (webList.size > 0 && webList[webList.lastIndex].title == title) {
            return
        }
        ll_error.visibility = View.VISIBLE
        ll_error.setOnClickListener({
            ll_error.isClickable = false
            if (isGetContent) {
                getContent()
            } else if (isGetImage) {
                getImage()
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webList.size > 1) {
            webList.removeAt(webList.lastIndex)
            showPage()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class MyWebViewClient : WebViewClient() {

        @TargetApi(21)
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return shouldOverrideUrlLoading(view!!, request!!.url.toString())
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (url.startsWith("file:///wiki/")) {
                title = URLDecoder.decode(url.substring(13), "UTF-8")
                if (title.startsWith("File:")) {
                    //暂时无法播放视频
                    if (!title.endsWith(".video")) {
                        getImage()
                    }
                } else {
                    //暂时不支持展示分类
                    if (!title.startsWith("Category:")) {
                        getContent()
                    }
                }
            } else {
                // 尝试使用浏览器打开
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("浏览器跳转错误", e.toString())
                }
            }
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progress_bar.visibility = View.GONE
            ll_error.visibility = View.GONE
            web_view.visibility = View.VISIBLE
            isGetContent = false
            isGetImage = false

            if (webList.size <= 0) {
                return
            }
            val webData = webList[webList.lastIndex]
            web_view.scrollTo(0, webData.scrollY)
        }
    }
}
