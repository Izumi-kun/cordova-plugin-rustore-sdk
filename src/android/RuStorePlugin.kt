package org.apache.cordova.plugin

import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CallbackContext

import org.json.JSONArray
import org.json.JSONObject

import android.content.Intent

import ru.rustore.sdk.core.tasks.OnCompleteListener
import ru.rustore.sdk.core.feature.model.FeatureAvailabilityResult

import ru.vk.store.sdk.review.RuStoreReviewManager
import ru.vk.store.sdk.review.RuStoreReviewManagerFactory

import ru.vk.store.sdk.review.model.ReviewInfo

import ru.rustore.sdk.billingclient.RuStoreBillingClient
import ru.rustore.sdk.billingclient.RuStoreBillingClientFactory

import ru.rustore.sdk.billingclient.model.product.Product
import ru.rustore.sdk.billingclient.model.product.ProductType
import ru.rustore.sdk.billingclient.model.product.ProductStatus

import ru.rustore.sdk.billingclient.model.purchase.Purchase
import ru.rustore.sdk.billingclient.model.purchase.PaymentResult
import ru.rustore.sdk.billingclient.model.purchase.PurchaseState

class RuStorePlugin : CordovaPlugin() {

  lateinit var reviewManager: RuStoreReviewManager
  private var billingClient: RuStoreBillingClient? = null

  /**
   * Called when initializing the plugin
   */
  override fun pluginInitialize() {
    super.pluginInitialize()

    this.reviewManager = RuStoreReviewManagerFactory.create(this.cordova.context)
  }

  /**
  * Called when executing an action from the JS code
  *
  * @param action A string naming the action
  * @param args JSON array containing all the arguments this action was called with
  * @param callbackContext The callback context used when calling back into JS code
  * @return true if success, false otherwise
  */
  override fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
    when (action) {
      // Reviews
      "openReviewForm" -> {
        openReviewForm(callbackContext)
        return true
      }
      // Purchases
      "initPurchases" -> { // TODO: initBillingClient?
        initBillingClient(args, callbackContext)
        return true
      }
      "checkPurchasesAvailability" -> {
        checkPurchasesAvailability(callbackContext)
        return true
      }
      "getProducts" -> {
        getProducts(args, callbackContext)
        return true
      }
      "getPurchases" -> {
        getPurchases(callbackContext)
        return true
      }
      "purchaseProduct" -> {
        purchaseProduct(args, callbackContext)
        return true
      }
      "confirmPurchase" -> {
        confirmPurchase(args, callbackContext)
        return true
      }
      "deletePurchase" -> {
        deletePurchase(args, callbackContext)
        return true
      }
      else -> return false
    }
  }

  /**
  * Called when the activity receives a new intent
  *
  * @param intent The new intent
  */
  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    billingClient?.onNewIntent(intent)
  }

  /**
  * Called to open the app review form which allows to leave a rating and a
  * comment about the app without quitting it. The user can rate the app
  * from 1 to 5 and leave an optional review
  *
  * @param callbackContext The callback context used when calling back into JS code
   */
  private fun openReviewForm(callbackContext: CallbackContext) {
    // Call requestReviewFlow before calling the launchReviewFlow in order to prepare the necessary info to display the screen
    reviewManager.requestReviewFlow()
      .addOnCompleteListener(object : OnCompleteListener<ReviewInfo> {
        override fun onSuccess(result: ReviewInfo) {
          // Save the received review info for further interaction
          // NOTE: the review info has a lifespan of ~5 mins
          val reviewInfo = result

          // Actually open the review and rating form
          // NOTE: after the interaction with the review form is complete it's not recommended to display any other forms related to review or rating, no matter which the result (either onSuccess or onFailure)
          // NOTE: the frequent calls to launchReviewFlow won't actually display the form with the same frequency as that is controlled on the RuStore side
          reviewManager.launchReviewFlow(reviewInfo)
            .addOnCompleteListener(object : OnCompleteListener<Unit> {
              override fun onSuccess(result: Unit) {
                // Review flow has finished, continue the app flow
                callbackContext.success()
              }

              override fun onFailure(throwable: Throwable) {
                // Review flow has finished, continue the app flow
                callbackContext.error("Failed to open the review form! ($throwable)")
              }
            })
        }

        override fun onFailure(throwable: Throwable) {
          // NOTE: it is not recommended to display an error to the user here as he's not the one who launched the process
          callbackContext.error("Failed to open the review form! ($throwable)")
        }
      })
  }

  /**
  * Called to initialize the billing client
  *
  * @param args JSON array containing all the arguments this action was called with
  * @param callbackContext The callback context used when calling back into JS code
  */
  private fun initBillingClient(args: JSONArray, callbackContext: CallbackContext) {
    val options = args.getJSONObject(0)

    billingClient = RuStoreBillingClientFactory.create(
      context = this.cordova.activity.application,
      consoleApplicationId = options.getString("consoleApplicationId"),
      deeplinkScheme = options.getString("deeplinkScheme"),
    )
    callbackContext.success()
  }

  /**
  * Called to check if purchases are available for the user
  *
  * @param callbackContext The callback context used when calling back into JS code
  */
  private fun checkPurchasesAvailability(callbackContext: CallbackContext) {
    if (billingClient == null) {
      callbackContext.error("billingClient is not initialized")
      return
    }

    billingClient!!.purchases.checkPurchasesAvailability()
      .addOnSuccessListener { result ->
        when (result) {
          FeatureAvailabilityResult.Available -> {
            callbackContext.success("Purchases availability check: Available")
          }

          is FeatureAvailabilityResult.Unavailable -> {
            callbackContext.error("Purchases availability check: Unavailable")
          }
        }
      }
      .addOnFailureListener { throwable ->
        callbackContext.error("Purchases availability check failed! ($throwable)")
      }
  }

  /**
  */
  // TODO: private fun isBillingClientInitialized(args: JSONArray, callbackContext: CallbackContext)?

  /**
  * Called to get the list of app's products
  *
  * @param args JSON array containing all the arguments this action was called with
  * @param callbackContext The callback context used when calling back into JS code
  */
  private fun getProducts(args: JSONArray, callbackContext: CallbackContext) {
    if (billingClient == null) {
      callbackContext.error("billingClient is not initialized")
      return
    }

    val productsArray = args.getJSONArray(0)
    val productIds = List<String>(productsArray.length()) {
      productsArray.getString(it)
    }

    billingClient!!.products.getProducts(productIds)
      .addOnSuccessListener { products: List<Product> ->
        val productsJson = JSONArray()
        products.forEach { product ->
          val productJson = JSONObject()

          productJson.put("productId", product.productId)

          product.productType?.let {
            // Possible types:
            // CONSUMABLE - can be purchased multiple times, represents things like crystals, for example
            // NON-CONSUMABLE - can be purchased only once, for things like ad disabling
            // SUBSCRIPTION - can be purchased for a time period, for things like streaming service subscription
            val type = when (it) {
              ProductType.CONSUMABLE -> "CONSUMABLE"
              ProductType.NON_CONSUMABLE -> "NON-CONSUMABLE"
              ProductType.SUBSCRIPTION -> "SUBSCRIPTION"
            }
            productJson.put("productType", type)
          }

          val status = when (product.productStatus) {
            ProductStatus.ACTIVE -> "ACTIVE"
            ProductStatus.INACTIVE -> "INACTIVE"
          }
          productJson.put("productStatus", status)

          product.priceLabel?.let {
            productJson.put("priceLabel", it)
          }

          product.price?.let {
            productJson.put("price", it)
          }

          product.currency?.let {
            productJson.put("currency", it)
          }

          product.language?.let {
            productJson.put("language", it)
          }

          product.title?.let {
            productJson.put("title", it)
          }

          product.description?.let {
            productJson.put("description", it)
          }

          product.imageUrl?.let {
            productJson.put("imageUrl", it.toString())
          }

          product.promoImageUrl?.let {
            productJson.put("promoImageUrl", it.toString())
          }

          product.subscription?.let { subscription ->
            val sub = JSONObject()

            subscription.subscriptionPeriod?.let {
              val period = JSONObject()
              period.put("years", it.years)
              period.put("months", it.months)
              period.put("days", it.days)
              sub.put("subscriptionPeriod", period)
            }

            subscription.freeTrialPeriod?.let {
              val period = JSONObject()
              period.put("years", it.years)
              period.put("months", it.months)
              period.put("days", it.days)
              sub.put("freeTrialPeriod", period)
            }

            subscription.gracePeriod?.let {
              val period = JSONObject()
              period.put("years", it.years)
              period.put("months", it.months)
              period.put("days", it.days)
              sub.put("gracePeriod", period)
            }

            subscription.introductoryPrice?.let {
              sub.put("introductoryPrice", it)
            }

            subscription.introductoryPriceAmount?.let {
              sub.put("introductoryPriceAmount", it)
            }

            subscription.introductoryPricePeriod?.let {
              val period = JSONObject()
              period.put("years", it.years)
              period.put("months", it.months)
              period.put("days", it.days)
              sub.put("introductoryPricePeriod", period)
            }

            productJson.put("subscription", sub)
          }

          productsJson.put(productJson)
        }
        callbackContext.success(productsJson)
      }
      .addOnFailureListener { throwable: Throwable ->
        callbackContext.error("Failed to get the products! ($throwable)")
      }
  }

  /**
  * Called to get the list of app's purchases
  *
  * @param callbackContext The callback context used when calling back into JS code
  */
  private fun getPurchases(callbackContext: CallbackContext) {
    if (billingClient == null) {
      callbackContext.error("billingClient is not initialized")
      return
    }

    billingClient!!.purchases.getPurchases()
      .addOnSuccessListener { purchases: List<Purchase> ->
        val purchasesJson = JSONArray()
        purchases.forEach { purchase ->
          val purchaseId = purchase.purchaseId
          if (purchaseId != null) {
            when(purchase.purchaseState) {
              PurchaseState.CREATED, PurchaseState.INVOICE_CREATED -> {
                billingClient!!.purchases.deletePurchase(purchaseId).await()
              }
              PurchaseState.PAID -> {
                billingClient!!.purchases.confirmPurchase(purchaseId).await()
              }
              else -> Unit
            }
          }

          val purchaseJson = JSONObject()

          purchase.purchaseId?.let {
            purchaseJson.put("purchaseId", it)
          }

          purchaseJson.put("productId", purchase.productId)

          purchase.productType?.let {
            // Possible types:
            // CONSUMABLE - can be purchased multiple times, represents things like crystals, for example
            // NON-CONSUMABLE - can be purchased only once, for things like ad disabling
            // SUBSCRIPTION - can be purchased for a time period, for things like streaming service subscription
            val type = when(it) {
              ProductType.CONSUMABLE -> "CONSUMABLE"
              ProductType.NON_CONSUMABLE -> "NON-CONSUMABLE"
              ProductType.SUBSCRIPTION -> "SUBSCRIPTION"
            }
            purchaseJson.put("productType", type)
          }

          purchase.invoiceId?.let {
            purchaseJson.put("invoiceId", it)
          }

          purchase.description?.let {
            purchaseJson.put("description", it)
          }

          purchase.language?.let {
            purchaseJson.put("language", it)
          }

          purchase.purchaseTime?.let {
            purchaseJson.put("purchaseTime", it.toString()) // TODO: it.toUTCString()/toJSON()/toDateString()?
          }

          purchase.orderId?.let {
            purchaseJson.put("orderId", it)
          }

          purchase.amountLabel?.let {
            purchaseJson.put("amountLabel", it)
          }

          purchase.amount?.let {
            purchaseJson.put("amount", it)
          }

          purchase.currency?.let {
            purchaseJson.put("currency", it)
          }

          purchase.quantity?.let {
            purchaseJson.put("quantity", it)
          }

          purchase.purchaseState?.let {
            val state = when(it) {
              PurchaseState.CREATED -> "CREATED"
              PurchaseState.INVOICE_CREATED -> "INVOICE CREATED"
              PurchaseState.CONFIRMED -> "CONFIRMED"
              PurchaseState.PAID -> "PAID"
              PurchaseState.CANCELLED -> "CANCELLED"
              PurchaseState.CONSUMED -> "CONSUMED"
              PurchaseState.CLOSED -> "CLOSED"
              PurchaseState.TERMINATED -> "TERMINATED"
            }
            purchaseJson.put("purchaseState", state)
          }

          purchase.developerPayload?.let {
            purchaseJson.put("developerPayload", it)
          }

          purchase.subscriptionToken?.let {
            purchaseJson.put("subscriptionToken", it)
          }

          purchasesJson.put(purchaseJson)
        }
        callbackContext.success(purchasesJson)
      }
      .addOnFailureListener { throwable: Throwable ->
        callbackContext.error("Failed to get the purchases! ($throwable)")
      }
  }

  /**
  * Called to make a purchase
  *
  * @param args JSON array containing all the arguments this action was called with
  * @param callbackContext The callback context used when calling back into JS code
  */
  private fun purchaseProduct(args: JSONArray, callbackContext: CallbackContext) {
    if (billingClient == null) {
      callbackContext.error("billingClient is not initialized")
      return
    }

    val data = args.getJSONObject(0)

    val productId = data.getString("productId")

    if (productId.isEmpty())
      callbackContext.error("Empty product ID provided!")

    val orderId = if (data.has("orderId")) data.getString("orderId") else null
    val quantity = if (data.has("quantity")) data.getInt("quantity") else 1
    val developerPayload =
      if (data.has("developerPayload")) data.getString("developerPayload") else null

    billingClient!!.purchases.purchaseProduct(productId, orderId, quantity, developerPayload)
      .addOnSuccessListener { result: PaymentResult ->
        when (result) {
          is PaymentResult.Success -> {
            val response = JSONObject()
            response.put("orderId", result.orderId)
            response.put("purchaseId", result.purchaseId)
            response.put("productId", result.productId)

            result.subscriptionToken?.let {
              response.put("subscriptionToken", it)
            }
            callbackContext.success(response)
          }
          is PaymentResult.Cancelled -> {
            billingClient!!.purchases.deletePurchase(result.purchaseId)
          }
          is PaymentResult.Failure -> {
            result.purchaseId?.let { billingClient!!.purchases.deletePurchase(it) }
          }
          // No payment state received during the payment
          is PaymentResult.InvalidPaymentState -> {
            callbackContext.error("Failed to purchase the product - No payment state received during the payment process!")
          }
        }
      }
      .addOnFailureListener { throwable: Throwable ->
        callbackContext.error("Failed to purchase the product! ($throwable)")
      }
  }

  /**
  * Called to delete the specified purchase
  *
  * @param args JSON array containing all the arguments this action was called with
  * @param callbackContext The callback context used when calling back into JS code
  */
  private fun deletePurchase(args: JSONArray, callbackContext: CallbackContext) {
    if (billingClient == null) {
      callbackContext.error("billingClient is not initialized")
      return
    }

    val purchaseId = args.getString(0)

    if (purchaseId.isEmpty())
      callbackContext.error("Empty purchase ID provided!")

    billingClient!!.purchases.deletePurchase(purchaseId)
      .addOnSuccessListener {
        callbackContext.success()
      }
      .addOnFailureListener { throwable: Throwable ->
        callbackContext.error("Failed to delete/cancel the purchase! ($throwable)")
      }
  }

  /**
   * Called to confirm the specified purchase
   *
   * @param args JSON array containing all the arguments this action was called with
   * @param callbackContext The callback context used when calling back into JS code
   */
  private fun confirmPurchase(args: JSONArray, callbackContext: CallbackContext) {
    if (billingClient == null) {
      callbackContext.error("billingClient is not initialized")
      return
    }

    val purchaseId = args.getString(0)
    val developerPayload = if (args.isNull(1)) null else args.getString(1)

    if (purchaseId.isEmpty())
      callbackContext.error("Empty purchase ID provided!")

    billingClient!!.purchases.confirmPurchase(purchaseId, developerPayload)
      .addOnSuccessListener {
        callbackContext.success()
      }
      .addOnFailureListener { throwable: Throwable ->
        callbackContext.error("Failed to confirm/consume the purchase! ($throwable)")
      }
  }
}
