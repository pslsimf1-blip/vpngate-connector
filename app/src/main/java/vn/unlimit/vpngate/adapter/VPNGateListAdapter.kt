package vn.unlimit.vpngate.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.App.Companion.instance
import vn.unlimit.vpngate.R
import vn.unlimit.vpngate.customview.ThreadSafeNativeAdView
import vn.unlimit.vpngate.models.VPNGateConnectionList
import vn.unlimit.vpngate.utils.DataUtil

class VPNGateListAdapter(private val mContext: Context) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val _list = VPNGateConnectionList()
    private val layoutInflater: LayoutInflater = LayoutInflater.from(mContext)
    private var onItemClickListener: OnItemClickListener? = null
    private var onItemLongClickListener: OnItemLongClickListener? = null
    private var onScrollListener: OnScrollListener? = null
    private var lastPosition = 0
    private var nativeAd: NativeAd? = null
    private var hasAds: Boolean = false
    private var adUnitId: String? = null

    // НОВОЕ: множество hostName серверов, помеченных сканером как проверенные
    private var verifiedHosts: Set<String> = emptySet()

    @SuppressLint("NotifyDataSetChanged")
    fun initialize(vpnGateConnectionList: VPNGateConnectionList?) {
        try {
            Log.d(TAG, "initialize with: ${vpnGateConnectionList?.size()} items")
            _list.clear()
            if (vpnGateConnectionList != null) {
                _list.addAll(vpnGateConnectionList)
            }
            notifyDataSetChanged()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * НОВОЕ: устанавливает список hostName, у которых isVerified = true.
     * Вызывается из HomeFragment после чтения из БД.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setVerifiedHosts(hosts: Set<String>) {
        this.verifiedHosts = hosts
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(inOnItemClickListener: OnItemClickListener?) {
        this.onItemClickListener = inOnItemClickListener
    }

    fun setOnItemLongClickListener(inOnItemLongPressListener: OnItemLongClickListener?) {
        this.onItemLongClickListener = inOnItemLongPressListener
    }

    fun setOnScrollListener(inOnScrollListener: OnScrollListener?) {
        this.onScrollListener = inOnScrollListener
    }

    fun setHasAds(hasAds: Boolean) {
        this.hasAds = hasAds
    }

    fun setAdUnitId(adUnitId: String?) {
        this.adUnitId = adUnitId
    }

    private fun shouldShowAdAt(position: Int): Boolean {
        if (!hasAds || adUnitId == null) return false
        return position > 0 && position % AD_INTERVAL == 2
    }

    private fun getRealPosition(position: Int): Int {
        if (!hasAds || adUnitId == null) return position
        return position - ((position + 2) / AD_INTERVAL)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadNativeAd(adViewHolder: VHTypeAd) {
        if (adUnitId == null) return
        App.runWhenInitialized {
            val adRequest = NativeAdRequest.Builder(adUnitId!!, listOf(NativeAd.NativeAdType.NATIVE)).build()
            NativeAdLoader.load(adRequest, object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    mainHandler.post {
                        this@VPNGateListAdapter.nativeAd = nativeAd
                        populateNativeAdView(nativeAd, adViewHolder)
                    }
                }

                override fun onCustomNativeAdLoaded(customNativeAd: com.google.android.libraries.ads.mobile.sdk.nativead.CustomNativeAd) {
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Native ad failed to load: ${adError.message}")
                }
            })
        }
    }

    private fun populateNativeAdView(nativeAd: NativeAd, adViewHolder: VHTypeAd) {
        adViewHolder.adLoadingContainer.visibility = View.GONE
        adViewHolder.nativeAdWrapper.visibility = View.VISIBLE

        val adWrapper = adViewHolder.nativeAdWrapper

        adWrapper.setHeadlineView(adViewHolder.adHeadline)
        adWrapper.setBodyView(adViewHolder.adBody)
        adWrapper.setCallToActionView(adViewHolder.adCallToAction)
        adWrapper.setIconView(adViewHolder.adAppIcon)
        adWrapper.setPriceView(adViewHolder.adPrice)
        adWrapper.setStarRatingView(adViewHolder.adStars)
        adWrapper.setStoreView(adViewHolder.adStore)
        adWrapper.setAdvertiserView(adViewHolder.adAdvertiser)

        adViewHolder.adHeadline.text = nativeAd.headline
        adViewHolder.adHeadline.visibility = if (nativeAd.headline != null) View.VISIBLE else View.GONE

        if (nativeAd.mediaContent != null) {
            adViewHolder.adMedia.mediaContent = nativeAd.mediaContent
            adViewHolder.adMedia.visibility = View.VISIBLE
        } else {
            adViewHolder.adMedia.visibility = View.GONE
        }

        if (nativeAd.body != null) {
            adViewHolder.adBody.text = nativeAd.body
            adViewHolder.adBody.visibility = View.VISIBLE
        } else {
            adViewHolder.adBody.visibility = View.GONE
        }

        if (nativeAd.callToAction != null) {
            adViewHolder.adCallToAction.text = nativeAd.callToAction
            adViewHolder.adCallToAction.visibility = View.VISIBLE
        } else {
            adViewHolder.adCallToAction.visibility = View.GONE
        }

        if (nativeAd.icon != null) {
            adViewHolder.adAppIcon.setImageDrawable(nativeAd.icon!!.drawable)
            adViewHolder.adAppIcon.visibility = View.VISIBLE
        } else {
            adViewHolder.adAppIcon.visibility = View.GONE
        }

        if (nativeAd.price != null) {
            adViewHolder.adPrice.text = nativeAd.price
            adViewHolder.adPrice.visibility = View.VISIBLE
        } else {
            adViewHolder.adPrice.visibility = View.GONE
        }

        if (nativeAd.starRating != null) {
            adViewHolder.adStars.rating = nativeAd.starRating!!.toFloat()
            adViewHolder.adStars.visibility = View.VISIBLE
        } else {
            adViewHolder.adStars.visibility = View.GONE
        }

        if (nativeAd.store != null) {
            adViewHolder.adStore.text = nativeAd.store
            adViewHolder.adStore.visibility = View.VISIBLE
        } else {
            adViewHolder.adStore.visibility = View.GONE
        }

        if (nativeAd.advertiser != null) {
            adViewHolder.adAdvertiser.text = nativeAd.advertiser
            adViewHolder.adAdvertiser.visibility = View.VISIBLE
        } else {
            adViewHolder.adAdvertiser.visibility = View.GONE
        }

        adWrapper.registerNativeAd(nativeAd, adViewHolder.adMedia)
    }

    override fun getItemViewType(position: Int): Int {
        return if (shouldShowAdAt(position)) {
            TYPE_AD
        } else {
            TYPE_NORMAL
        }
    }

    override fun onBindViewHolder(
        viewHolder: RecyclerView.ViewHolder,
        @SuppressLint("RecyclerView") position: Int
    ) {
        if (onScrollListener != null) {
            if (position > lastPosition || position == 0) {
                onScrollListener!!.onScrollDown()
            } else if (position < lastPosition) {
                onScrollListener!!.onScrollUp()
            }
        }
        when (viewHolder) {
            is VHTypeVPN -> viewHolder.bindViewHolder(getRealPosition(position))
            is VHTypeAd -> loadNativeAd(viewHolder)
        }
        lastPosition = position
    }

    override fun getItemCount(): Int {
        val itemCount = _list.size()
        if (!hasAds || adUnitId == null || itemCount == 0) {
            return itemCount
        }
        return itemCount + (itemCount / AD_INTERVAL)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_AD -> {
                val view = layoutInflater.inflate(R.layout.item_native_ad, parent, false)
                VHTypeAd(view)
            }
            else -> {
                val view = layoutInflater.inflate(R.layout.item_vpn, parent, false)
                VHTypeVPN(view)
            }
        }
    }

    private inner class VHTypeVPN(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnClickListener, OnLongClickListener {
        var imgFlag: ImageView = itemView.findViewById(R.id.img_flag)
        var txtCountry: TextView = itemView.findViewById(R.id.txt_country)
        var txtIp: TextView = itemView.findViewById(R.id.txt_ip)
        var txtHostname: TextView = itemView.findViewById(R.id.txt_hostname)
        var txtScore: TextView = itemView.findViewById(R.id.txt_score)
        var txtUptime: TextView = itemView.findViewById(R.id.txt_uptime)
        var txtSpeed: TextView = itemView.findViewById(R.id.txt_speed)
        var txtPing: TextView = itemView.findViewById(R.id.txt_ping)
        var txtSession: TextView = itemView.findViewById(R.id.txt_session)
        var txtOwner: TextView = itemView.findViewById(R.id.txt_owner)
        var lnTCP: View = itemView.findViewById(R.id.ln_tcp)
        var txtTCP: TextView = itemView.findViewById(R.id.txt_tcp_port)
        var lnUDP: View = itemView.findViewById(R.id.ln_udp)
        var txtUDP: TextView = itemView.findViewById(R.id.txt_udp_port)
        var lnL2TP: View = itemView.findViewById(R.id.ln_l2tp)
        var lnSSTP: View = itemView.findViewById(R.id.ln_sstp)

        init {
            itemView.setOnLongClickListener(this)
            itemView.setOnClickListener(this)
        }

        fun bindViewHolder(position: Int) {
            try {
                val vpnGateConnection = _list.get(position)
                Glide.with(mContext)
                    .load(instance!!.dataUtil!!.baseUrl + "/images/flags/" + vpnGateConnection.countryShort + ".png")
                    .placeholder(R.color.colorOverlay)
                    .error(R.color.colorOverlay)
                    .into(imgFlag)
                txtCountry.text = vpnGateConnection.countryLong
                txtIp.text = vpnGateConnection.ip

                // НОВОЕ: подсветка проверенных серверов зелёной галочкой
                val isVerified = vpnGateConnection.hostName != null &&
                        verifiedHosts.contains(vpnGateConnection.hostName)
                if (isVerified) {
                    txtHostname.text = "✓ " + vpnGateConnection.calculateHostName
                    txtHostname.setTextColor(Color.parseColor("#4CAF50")) // зелёный Material
                } else {
                    txtHostname.text = vpnGateConnection.calculateHostName
                    txtHostname.setTextColor(itemView.context.getColor(android.R.color.primary_text_light))
                }

                txtScore.text = vpnGateConnection.scoreAsString
                txtUptime.text = vpnGateConnection.getCalculateUpTime(mContext)
                txtSpeed.text = vpnGateConnection.calculateSpeed
                txtPing.text = vpnGateConnection.pingAsString
                txtSession.text = vpnGateConnection.numVpnSessionAsString
                txtOwner.text = vpnGateConnection.operator
                val dataUtil = instance!!.dataUtil
                val isIncludeUdp = dataUtil!!.getBooleanSetting(DataUtil.INCLUDE_UDP_SERVER, true)
                if (!isIncludeUdp || vpnGateConnection.tcpPort == 0) {
                    lnTCP.visibility = View.GONE
                } else {
                    lnTCP.visibility = View.VISIBLE
                    txtTCP.text = vpnGateConnection.tcpPort.toString()
                }
                if (!isIncludeUdp || vpnGateConnection.udpPort == 0) {
                    lnUDP.visibility = View.GONE
                } else {
                    lnUDP.visibility = View.VISIBLE
                    txtUDP.text = vpnGateConnection.udpPort.toString()
                }
                lnL2TP.visibility =
                    if (vpnGateConnection.isL2TPSupport()) View.VISIBLE else View.GONE
                lnSSTP.visibility =
                    if (vpnGateConnection.isSSTPSupport()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "bindViewHolder error", e)
                e.printStackTrace()
            }
        }

        override fun onLongClick(view: View): Boolean {
            try {
                if (onItemLongClickListener != null) {
                    val clickedPost = getRealPosition(adapterPosition)
                    val item = _list.get(clickedPost)
                    onItemLongClickListener!!.onItemLongClick(item, clickedPost)
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        override fun onClick(view: View) {
            try {
                if (onItemClickListener != null) {
                    val clickedPost = getRealPosition(adapterPosition)
                    val item = _list.get(clickedPost)
                    onItemClickListener!!.onItemClick(item, clickedPost)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private inner class VHTypeAd(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nativeAdWrapper: ThreadSafeNativeAdView = itemView.findViewById(R.id.native_ad_view)
        val adMedia: com.google.android.libraries.ads.mobile.sdk.nativead.MediaView = itemView.findViewById(R.id.ad_media)
        val adHeadline: TextView = itemView.findViewById(R.id.ad_headline)
        val adBody: TextView = itemView.findViewById(R.id.ad_body)
        val adCallToAction: Button = itemView.findViewById(R.id.ad_call_to_action)
        val adAppIcon: ImageView = itemView.findViewById(R.id.ad_app_icon)
        val adPrice: TextView = itemView.findViewById(R.id.ad_price)
        val adStars: RatingBar = itemView.findViewById(R.id.ad_stars)
        val adStore: TextView = itemView.findViewById(R.id.ad_store)
        val adAdvertiser: TextView = itemView.findViewById(R.id.ad_advertiser)
        val adLoadingContainer: com.facebook.shimmer.ShimmerFrameLayout = itemView.findViewById(R.id.ad_loading_container)
    }

    companion object {
        private const val TYPE_NORMAL = 100000
        private const val TYPE_AD = 100001
        private const val TAG = "VPNGateListAdapter"
        private const val AD_INTERVAL = 4
    }
}