package com.example.bootiful_javafx;

/**
 * The one thing the OAuth dance needs from the operating system: put this URL in front of
 * the human. Keeping it behind an interface keeps the login flow testable without anybody
 * launching a browser.
 */
interface AuthorizationBrowser {

	void open(String authorizationRequestUri);

}
