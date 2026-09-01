import { FieldValue } from "firebase-admin/firestore";
import type { Message } from "firebase-admin/messaging";
import { logger } from "firebase-functions/v2";
import { db, messaging } from "./firebase";

/**
 * Sends a push to one user, looking their device token up on the way.
 *
 * Shared by every trigger in this codebase so the dead-token cleanup below
 * only has to exist once. Without it, a token that died with an uninstall
 * makes every future send to that user fail forever, silently.
 */
export async function sendToUser(
  recipientId: string,
  message: Omit<Message, "token">,
): Promise<boolean> {
  const snapshot = await db().collection("users").doc(recipientId).get();
  const token: string | undefined = snapshot.get("fcmToken");

  if (!token) {
    logger.info("No FCM token for recipient", { recipientId });
    return false;
  }

  try {
    await messaging().send({ ...message, token } as Message);
    return true;
  } catch (error: unknown) {
    const code = (error as { code?: string })?.code;

    if (code === "messaging/registration-token-not-registered") {
      await snapshot.ref.update({ fcmToken: FieldValue.delete() });
      logger.info("Cleared a dead FCM token", { recipientId });
      return false;
    }

    logger.error("Push failed", { recipientId, code });
    throw error;
  }
}

/** The other member of a pairing, or null while an invite is outstanding. */
export async function partnerOf(
  pairingId: string,
  senderId: string,
): Promise<string | null> {
  const pairing = await db().collection("pairings").doc(pairingId).get();
  const members: string[] = pairing.get("members") ?? [];
  return members.find((member) => member !== senderId) ?? null;
}

export async function displayNameOf(uid: string): Promise<string> {
  const user = await db().collection("users").doc(uid).get();
  return user.get("displayName") ?? "";
}
