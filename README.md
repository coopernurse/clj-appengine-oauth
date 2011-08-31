# clj-appengine-oauth

Very very simple oauth library for Clojure for use with Google App Engine.  This library uses the
Signpost library to do OAuth 1.0 signatures.

## Tested with

* Twitter (1.0a)
* Netflix (1.0a)
* Facebook (2.0)

It probably works with other providers. You may need to fork the code and hack it a bit if you need to pass arbitrary
parameters to the provider.  Signpost doesn't have an super obvious way to do that that I saw, but I probably missed it.

## Gotchas

* Signpost's OauthConsumer class, which we use, is stateful. Sorry I know, not very Clojure-esque.  At any rate you'll want to stash this object in the session so that you use the same instance per request.
* Signpost is not threadsafe -- make sure to create new consumers and provider instances from within your ring/compojure/noir request handlers

## Examples

It seems that every oauth provider does things a little differently.  This makes most OAuth libraries
hard to use.  Use the examples below to see how to play with the library in the REPL.  I suspect you'll
need to fiddle with it for each new provider you try it with.  Please send me updated docs/examples if you
try other providers.

For full example of how to use this library in a real Clojure/Noir/appengine-magic app,
please see: https://github.com/coopernurse/votenoir

Here are some abbreviated snippits

### Twitter - should work for OAuth 1.0 providers

    ;; first, bootstrap the app engine environment so we can fetch URLs
    user=> (require '[appengine-magic.local-env-helpers :as ae-helpers])
    nil
    user=> (ae-helpers/appengine-init (java.io.File. ".") 8090)
    nil

    ;; next, load our library
    user=> (require '[clj-appengine-oauth.core :as oauth])
    nil

    ;; setup your oauth app credentials and make a request
    user=> (def twitter-consumer (oauth/make-consumer "your-app-key" "your-app-secret"))
    #'user/twitter-consumer

    ;; ask twitter for the "login" page.  this is where you'd redirect a user when they ask
    ;; to login to your app from twitter
    user=> (oauth/get-authorize-url (oauth/make-provider-twitter) twitter-consumer "http://myapp.com/twitter-callback")
    "https://twitter.com/oauth/authorize?oauth_token=x6qIEP3S5RfxZcOXJmVAEfk9pFaScVfZFzlrauGDQdU"
    
    ;; after a successful login, twitter will hit your callback url with "oauth_verifier" on the query string
    ;; now you need to get an access token from them.  you'll use this to sign requests
    ;; on behalf of the user who just logged in
    ;;
    ;; example redirect from twitter that I cut/paste from my browser:
    ;;   http://www.myapp.com/twitter-callback?oauth_token=4tbyN90ymDTAWMnk8ouwyYnalezRZIlQpJntg5dYU&oauth_verifier=AejNj1GUrlW1MDnei8IqwygJtUuKg9zPdggJmar46wk
    ;;
    ;; this should return nil - but the twitter-consumer will have some state assigned
    ;; I know, I know, this isn't how you're supposed to write clojure..  Someday I'll get rid of this
    ;; Signpost code, but until then, bask in the glory of having something working with oauth
    ;;
    user=> (oauth/get-access-token (oauth/make-provider-twitter) twitter-consumer "AejNj1GUrlW1MDnei8IqwygJtUuKg9zPdggJmar46wk")
    nil

    ;; now you can access protected resources with your twitter-consumer
    user=> (oauth/get-protected-url twitter-consumer nil "http://api.twitter.com/1/account/verify_credentials.json" "utf-8")
    {\"profile_use_background_image\":true,\"favourite ... }

    ;; woohoo! -- at this point you should stash twitter-consumer on the user's session
    ;; with noir you'd do this: (session/put! :oauth-consumer twitter-consumer)

### Facebook (and maybe other OAuth 2.0 providers??)

    ;; setup your oauth app credentials
    user=> (def facebook-consumer (oauth/make-consumer "app-id" "app-secret"))
    #'user/facebook-consumer

    ;; ask facebook for the "login" page.
    user=> (oauth/get-authorize-url-facebook facebook-consumer "http://myapp.com/facebook-callback")
    "https://www.facebook.com/dialog/oauth?client_id=xxx&redirect_uri=http%3A%2F%2Fmyapp.com%2Ffacebook-callback"

    ;; you redirect the user to this page and wait..
    ;;
    ;; NOTE: Unlike Twitter, Facebook is VERY picky about the callback-url you pass in.  It MUST match
    ;;       the URL you configured for your Facebook app.  This is midly annoying during development.
    ;;       My suggestion is to register a test app that has a URL of:  http://localhost:8080
    ;;
    ;;       Test with that app, and use different app-id/secret credentials in production
    ;;
    ;; example redirect from Facebook:
    ;;   http://localhost:8080/facebook-callback?code=AQAncT5y4qVflZs_FM-_2E7_uhqhs-Lf-F5xTqUnVAvRNpMZA6jme1kbyup3qZ29KB-cArkkjWWVLd0oo83jCd6AUJ2lHXZoW9TuH1JEcANbdbJOV2q1mfoqjImiT9nzmKu2bPdWYqELcwwAqmzCv8p5HYYrlAh1ut7_eGBewuef-4jfMpbfLN7eGEaxveQw2UxmauRCXk6RsaFhLz_9c02z#_

    ;; ask facebook for an access_token
    ;; the 'code' variable here was assigned the value from the query string
    ;;
    ;; NOTE: make sure your callback-url is EXACTLY the same as the one you used in the
    ;;       get-authorize-url-facebook call.  If it's not you'll get an error
    ;;
    ;; The response map has two keys: :access_token and :expires
    ;;
    ;;   :access_token is what you'll use on future requests
    ;;   :expires is the number of seconds this token is good for.  It's up to you to track that
    ;;            once it expires you have to repeat the auth process again to get a new token
    ;;
    user=> (def facebook-access-token (oauth/get-access-token-facebook facebook-consumer code "http://localhost:8080/facebook-callback"))
    #'user/facebook-access-token

    ;;
    ;; Facebook is using OAuth 2, which doesn't use signatures.
    ;; This means you can throw away the facebook-consumer var at this point
    ;; The only thing you need to hold onto is this access token
    ;;
    ;; Now you can request a restricted page
    user=> (oauth/get-protected-url-facebook (:access_token facebook-access-token) "https://graph.facebook.com/me" "utf-8")
    "{\"id\":\"10000 .... }

    ;; woohoo! stash that facebook-access-token some place safe, like the session

    
## License

Copyright (C) 2011 James Cooper

Distributed under the Eclipse Public License, the same as Clojure.
