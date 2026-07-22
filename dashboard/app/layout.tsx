import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Reckon — event-sourced ledger",
  description: "Balances, transaction history, and the event-log replay viewer.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
