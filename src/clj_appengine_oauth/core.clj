(ns clj-appengine-oauth.core
  (:import
    (com.google.appengine.api.urlfetch URLFetchServiceFactory HTTPMethod HTTPResponse HTTPRequest HTTPHeader)
    (oauth.signpost.http HttpResponse HttpRequest)
    (oauth.signpost AbstractOAuthProvider AbstractOAuthConsumer)
    (java.net URL)
    (java.io ByteArrayInputStream)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers ;;
;;;;;;;;;;;;;

(defmacro dbg[x] `(let [x# ~x] (println '~x "=" x#) x#))

(defn debug-http-request [req]
  (let [body (.getPayload req)]
    (println "url=" (.toExternalForm (.getURL req)) "?" (.getQuery (.getURL req)))
    (if (not (nil? body)) (println "body=" (String. body))))
  req)

(defn debug-consumer [consumer]
  (println (.getConsumerKey consumer))
  (println (.getConsumerSecret consumer))
  (println (.getToken consumer))
  (println (.getTokenSecret consumer))
  consumer)

(defn url-encode [s]
  (java.net.URLEncoder/encode s "ISO-8859-1"))

(defn request-to-keywords [req]
  (into {} (for [[_ k v] (re-seq #"([^&=]+)=([^&]+)" req)]
    [(keyword k) (java.net.URLDecoder/decode v "ISO-8859-1")])))

(defn get-header
  [headers name]
  (let [match (filter #(= name (.getName %)) headers)]
    (if (empty? match) nil (.getValue (first match)))))

(defn get-url [url encoding]
  (let [req (HTTPRequest. (URL. url) HTTPMethod/GET)]
    (let [resp (.fetch (URLFetchServiceFactory/getURLFetchService) req)]
      (String. (.getContent resp) encoding))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; signpost/gae bindings ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;
;; Mostly a port of hleinone's signpost fork:
;; https://github.com/hleinone/signpost/tree/appengine/signpost-appengine/src/main/java/oauth/signpost/appengine
;;

(defn make-request-adapter
  [req]
  (proxy [HttpRequest] []
    (getMethod []
      (.name (.getMethod req)))
    (getRequestUrl []
      (.toString (.getURL req)))
    (setRequestUrl [url]
      (throw (UnsupportedOperationException. "")))
    (getHeader [name]
      (get-header (.getHeaders req) name))
    (setHeader [name value]
      (.addHeader req (HTTPHeader. name value)))
    (getAllHeaders []
      (array-map (flatten (map (fn [h] (list (.getName h) (.getValue h))) (.getHeaders req)))))
    (getContentType []
      (.getHeader this "Content-Type"))
    (getMessagePayload []
      (let [p (.getPayload req)]
        (if (nil? p) p (ByteArrayInputStream. p))))
    (unwrap []
      req)))

(defn make-response-adapter
  [resp]
  (proxy [HttpResponse] []
    (getStatusCode []
      (.getResponseCode resp))
    (getReasonPhrase []
      nil)
    (getContent []
      (ByteArrayInputStream. (.getContent resp)))
    (unwrap []
      resp)))

(defn make-consumer
  [key secret]
  (proxy [AbstractOAuthConsumer] [key secret]
    (wrap [req]
      (make-request-adapter req))))

(defn make-provider
  [request-token-url access-token-url authorize-url]
  (proxy [AbstractOAuthProvider] [request-token-url access-token-url authorize-url]
    (createRequest [url]
      (make-request-adapter (HTTPRequest. (URL. url) HTTPMethod/POST)))
    (sendRequest [req]
      (make-response-adapter (.fetch (URLFetchServiceFactory/getURLFetchService) (.unwrap req))))
    (closeConnection [req resp] )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; signpost/gae bindings ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-authorize-url
  [provider consumer callback-url]
  (.retrieveRequestToken provider consumer callback-url))

(defn get-authorize-url-facebook
  [consumer callback-url]
  (format
    "https://www.facebook.com/dialog/oauth?client_id=%s&redirect_uri=%s"
    (url-encode (.getConsumerKey consumer))
    (url-encode callback-url)))

(defn get-access-token
  [provider consumer oauth-verifier]
  (.retrieveAccessToken provider consumer oauth-verifier))

(defn get-access-token-facebook
  [consumer callback-code callback-url]
  (let [url (format
              "https://graph.facebook.com/oauth/access_token?redirect_uri=%s&client_id=%s&client_secret=%s&code=%s"
              (url-encode callback-url)
              (url-encode (.getConsumerKey consumer))
              (url-encode (.getConsumerSecret consumer))
              (url-encode callback-code))
        resp  (get-url url "utf-8")
        keyw  (request-to-keywords resp)]
    (if (:access_token keyw) keyw resp)))

(defn get-protected-url
  [consumer token url encoding]
  (let [req (HTTPRequest. (URL. url) HTTPMethod/GET)]
    (.sign consumer req)
    (let [resp (.fetch (URLFetchServiceFactory/getURLFetchService) req)]
      (String. (.getContent resp) encoding))))

(defn get-protected-url-facebook
  [token base-url encoding]
  (let [token-qs (str "access_token=" (url-encode token))
             url (if (.contains base-url "?")
                   (str base-url "&" token-qs)
                   (str base-url "?" token-qs))]
    (get-url url encoding)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; provider config examples - please fork/add more ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-provider-twitter
  []
  (make-provider
    "https://twitter.com/oauth/request_token"
    "https://twitter.com/oauth/access_token"
    "https://twitter.com/oauth/authorize"))

(defn make-provider-google
  [scope]
  (let [enc-sc (url-encode scope)
             p (make-provider
      (format "https://www.google.com/accounts/OAuthGetRequestToken?scope=%s" enc-sc)
      "https://www.google.com/accounts/OAuthGetAccessToken"
      "https://www.google.com/accounts/OAuthAuthorizeToken") ]
    (.setOAuth10a p true)
    p))

(defn make-provider-netflix
  [consumer app-name]
  (let [p (make-provider
    "http://api.netflix.com/oauth/request_token"
    "http://api.netflix.com/oauth/access_token"
    (format "https://api-user.netflix.com/oauth/login?oauth_consumer_key=%s&application_name=%s"
      (url-encode (.getConsumerKey consumer))
      (url-encode app-name)))]
    ;; according to posts on the netflix forum, you want
    ;; to force 1.0a
    (.setOAuth10a p true)
    p))

