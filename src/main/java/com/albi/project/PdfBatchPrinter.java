package com.albi.project;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.print.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPageable;

public class PdfBatchPrinter extends JFrame {

    private JTextField folderPathField;
    private JButton browseButton, startButton, stopButton;
    private JComboBox<String> printerComboBox;
    private JComboBox<String> sortComboBox;
    private JTextArea logArea;
    private JSpinner delaySpinner;
    private JLabel statusLabel;


    private List<File> pdfFiles = new ArrayList<>();
    private volatile boolean isPrinting = false;
    private ExecutorService executor;

    public  PdfBatchPrinter() {
        setTitle("PDF Batch Print Tool For ALBI GMBH");
        setSize(700, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        initUI();
        loadPrinters();
    }

    private void initUI() {
        JPanel panel = new JPanel(new BorderLayout(10,10));
        JPanel topPanel = new JPanel(new GridLayout(3,1,5,5));

        JPanel folderPanel = new JPanel(new BorderLayout(5,5));
        folderPathField = new JTextField();
        browseButton = new JButton("Browse...");
        folderPanel.add(new JLabel("Folder:"), BorderLayout.WEST);
        folderPanel.add(folderPathField, BorderLayout.CENTER);
        folderPanel.add(browseButton, BorderLayout.EAST);
        topPanel.add(folderPanel);

        JPanel printerPanel = new JPanel(new BorderLayout(5,5));
        printerComboBox = new JComboBox<>();
        printerPanel.add(new JLabel("Printer:"), BorderLayout.WEST);
        printerPanel.add(printerComboBox, BorderLayout.CENTER);
        topPanel.add(printerPanel);

        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sortComboBox = new JComboBox<>(new String[]{"Alphabetical", "Numerical"});
        delaySpinner = new JSpinner(new SpinnerNumberModel(2, 1, 10, 1)); // default 2 seconds
        optionsPanel.add(new JLabel("Sort:"));
        optionsPanel.add(sortComboBox);
        optionsPanel.add(new JLabel("Delay (sec):"));
        optionsPanel.add(delaySpinner);
        topPanel.add(optionsPanel);

        panel.add(topPanel, BorderLayout.NORTH);


        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        panel.add(scrollPane, BorderLayout.CENTER);


        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);


        statusLabel = new JLabel("Ready");
        panel.add(statusLabel, BorderLayout.PAGE_END);

        add(panel);


        browseButton.addActionListener(e -> chooseFolder());
        startButton.addActionListener(e -> startPrinting());
        stopButton.addActionListener(e -> stopPrinting());
    }

    private void loadPrinters() {
        PrintService[] printers = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService printer : printers) {
            printerComboBox.addItem(printer.getName());
        }
        if (printerComboBox.getItemCount() > 0) {
            printerComboBox.setSelectedIndex(0);
        }
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            folderPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startPrinting() {
        String folderPath = folderPathField.getText().trim();
        if (folderPath.isEmpty() || !new File(folderPath).exists()) {
            JOptionPane.showMessageDialog(this, "Please select a valid folder.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }


        pdfFiles.clear();
        File folder = new File(folderPath);
        boolean recursive = true; // optional: add checkbox for recursive
        gatherPDFFiles(folder, recursive);

        if (pdfFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No PDF files found in the selected folder.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }


        String sortOption = (String) sortComboBox.getSelectedItem();
        if ("Alphabetical".equals(sortOption)) {
            pdfFiles.sort(Comparator.comparing(File::getName));
        } else if ("Numerical".equals(sortOption)) {
            pdfFiles.sort(Comparator.comparingInt(f -> extractNumber(f.getName())));
        }


        isPrinting = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        logArea.setText("");
        statusLabel.setText("Starting...");

        int delaySeconds = (Integer) delaySpinner.getValue();

        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                PrintService selectedPrinter = findPrinterByName((String) printerComboBox.getSelectedItem());
                if (selectedPrinter == null) {
                    appendLog("Printer not found.");
                    return;
                }
                int totalFiles = pdfFiles.size();
                int currentIndex = 0;

                for (File pdfFile : pdfFiles) {
                    if (!isPrinting) {
                        appendLog("Printing stopped.");
                        break;
                    }
                    currentIndex++;
                    updateStatus("Printing file " + currentIndex + " of " + totalFiles + ": " + pdfFile.getName());

                    boolean success = printPDF(pdfFile, selectedPrinter);
                    if (success) {
                        appendLog("Printed: " + pdfFile.getName());
                    } else {
                        appendLog("Failed: " + pdfFile.getName());
                    }

                    // Delay
                    Thread.sleep(delaySeconds * 1000);
                }
            } catch (Exception e) {
                appendLog("Error: " + e.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    isPrinting = false;
                    startButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    statusLabel.setText("Done");
                });
            }
        });
    }

    private void stopPrinting() {
        isPrinting = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusLabel.setText("Stopped");
        appendLog("Printing stopped by user.");
    }

    private void gatherPDFFiles(File folder, boolean recursive) {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory() && recursive) {
                gatherPDFFiles(file, true);
            } else if (file.isFile() && file.getName().toLowerCase().endsWith(".pdf")) {
                pdfFiles.add(file);
            }
        }
    }

    private int extractNumber(String filename) {
        try {
            String numStr = filename.replaceAll("[^0-9]", "");
            return Integer.parseInt(numStr);
        } catch (Exception e) {
            return 0;
        }
    }

    private PrintService findPrinterByName(String name) {
        for (PrintService ps : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (ps.getName().equalsIgnoreCase(name)) {
                return ps;
            }
        }
        return null;
    }

    private boolean printPDF(File file, PrintService printer) {
        try (PDDocument document = PDDocument.load(file)) {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(printer);
            job.setPageable(new PDFPageable(document));
            job.print();
            return true;
        } catch (IOException | PrinterException e) {
            appendLog("Error printing " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }


    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }


    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new PdfBatchPrinter().setVisible(true);
        });
    }
}