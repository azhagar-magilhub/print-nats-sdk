package com.magilhub.printnats.desktop;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.PrinterTransport;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;

/**
 * USB (and any spooler-installed) printers via the Windows print queue: raw ESC/POS / StarPRNT bytes sent to the
 * printer named in {@code address}. The printer must be installed in Windows (vendor driver or "Generic / Text
 * Only"). The spooler accepts the job; it cannot confirm the ticket physically printed.
 */
public final class WindowsQueueTransport implements PrinterTransport {
    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        PrintService service = find(printer.address);
        if (service == null) {
            return new PrintResult(PrintOutcome.FAULT, "No USB printer reachable (Windows printer '" + printer.address + "' not installed)");
        }
        try {
            DocPrintJob job = service.createPrintJob();
            Doc doc = new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
            job.print(doc, null);
            return PrintResult.success();
        } catch (PrintException e) {
            return new PrintResult(PrintOutcome.CONNECTION_FAILED, "Unable to connect to printer while preparing the print job. (" + e.getMessage() + ")");
        }
    }

    static PrintService find(String name) {
        if (name == null) return null;
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (s.getName().equalsIgnoreCase(name.trim())) return s;
        }
        return null;
    }

    /** Installed Windows printers (for the printer-setup UI). */
    public static String[] installedPrinters() {
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) names[i] = all[i].getName();
        return names;
    }
}
