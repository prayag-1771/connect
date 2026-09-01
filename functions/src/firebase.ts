import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";

/**
 * Lazy accessors rather than a top-level initializeApp().
 *
 * Module-level initialisation depends on import order, and a helper module
 * that reaches for Firestore while being imported can easily run before the
 * entry point has initialised the app. Going through these functions means the
 * order stops mattering.
 */
function app() {
  if (getApps().length === 0) {
    initializeApp();
  }
  return getApps()[0];
}

export function db() {
  app();
  return getFirestore();
}

export function messaging() {
  app();
  return getMessaging();
}
