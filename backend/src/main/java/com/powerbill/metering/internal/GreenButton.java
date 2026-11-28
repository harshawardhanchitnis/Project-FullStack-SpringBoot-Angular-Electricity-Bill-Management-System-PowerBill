package com.powerbill.metering.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.powerbill.metering.MeterUsage.Reading;

@Component
class GreenButton {
    private static final String ESPI = "http://naesb.org/espi";
    private static final String ATOM = "http://www.w3.org/2005/Atom";

    String export(List<Reading> readings) {
        String updated = Instant.now().toString();
        String root = "/espi/UsagePoint/" + UUID.randomUUID();
        StringBuilder out = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"
                    xmlns:espi="http://naesb.org/espi">
                """);
        out.append("<id>urn:uuid:").append(UUID.randomUUID()).append("</id>")
                .append("<title>GridIQ electricity usage</title><updated>").append(updated).append("</updated>")
                .append("<author><name>PowerBill GridIQ</name></author>");
        entry(out, root, "/espi/UsagePoint", null, "Usage Point", updated,
                "<espi:UsagePoint><espi:ServiceCategory><espi:kind>0</espi:kind></espi:ServiceCategory></espi:UsagePoint>");
        List<Reading> usable = readings.stream().filter(reading -> !reading.quality().equals("FLAGGED")).toList();
        channel(out, root, updated, "1", "Imported electricity", usable, Reading::importKwh);
        channel(out, root, updated, "19", "Exported electricity", usable, Reading::exportKwh);
        return out.append("</feed>").toString();
    }

    private void channel(StringBuilder out, String root, String updated, String direction, String label,
            List<Reading> readings, Function<Reading, BigDecimal> value) {
        String meter = root + "/MeterReading/" + direction;
        String type = meter + "/ReadingType";
        entry(out, meter, root, type, label, updated, "<espi:MeterReading/>");
        entry(out, type, meter, null, "Hourly watt-hours", updated, """
                <espi:ReadingType>
                  <espi:accumulationBehaviour>4</espi:accumulationBehaviour>
                  <espi:commodity>1</espi:commodity>
                  <espi:flowDirection>%s</espi:flowDirection>
                  <espi:intervalLength>3600</espi:intervalLength>
                  <espi:powerOfTenMultiplier>-1</espi:powerOfTenMultiplier>
                  <espi:uom>72</espi:uom>
                </espi:ReadingType>
                """.formatted(direction));

        StringBuilder block = new StringBuilder("<espi:IntervalBlock>");
        if (!readings.isEmpty()) {
            long start = readings.getFirst().start().getEpochSecond();
            long elapsed = readings.getLast().start().getEpochSecond() + 3600 - start;
            block.append("<espi:interval><espi:duration>").append(elapsed)
                    .append("</espi:duration><espi:start>").append(start).append("</espi:start></espi:interval>");
        }
        for (Reading reading : readings) {
            block.append("<espi:IntervalReading>");
            if (reading.quality().equals("ESTIMATED")) {
                block.append("<espi:ReadingQuality><espi:quality>8</espi:quality></espi:ReadingQuality>");
            }
            block.append("<espi:timePeriod><espi:duration>3600</espi:duration><espi:start>")
                    .append(reading.start().getEpochSecond()).append("</espi:start></espi:timePeriod><espi:value>")
                    .append(value.apply(reading).movePointRight(4).toBigIntegerExact())
                    .append("</espi:value></espi:IntervalReading>");
        }
        entry(out, meter + "/IntervalBlock/1", meter, null, label + " readings", updated,
                block.append("</espi:IntervalBlock>").toString());
    }

    private void entry(StringBuilder out, String self, String up, String related,
            String title, String updated, String xml) {
        out.append("<entry><id>urn:uuid:").append(UUID.randomUUID()).append("</id><title>")
                .append(title).append("</title><updated>").append(updated).append("</updated>")
                .append("<link rel=\"self\" href=\"").append(self).append("\"/>")
                .append("<link rel=\"up\" href=\"").append(up).append("\"/>");
        if (related != null) {
            out.append("<link rel=\"related\" href=\"").append(related).append("\"/>");
        }
        out.append("<content type=\"application/xml\">").append(xml).append("</content></entry>");
    }

    List<MeteringService.Input> parse(String xml) {
        if (xml.length() > 2_000_000) {
            throw invalid();
        }
        var types = new HashMap<String, Type>();
        var relations = new HashMap<String, List<String>>();
        var samples = new ArrayList<Sample>();
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            var reader = factory.createXMLStreamReader(new java.io.StringReader(xml));
            String root = null;
            String self = null;
            String up = null;
            List<String> related = new ArrayList<>();
            int multiplier = 0;
            int direction = 0;
            int unit = 0;
            long start = 0;
            long duration = 0;
            BigDecimal value = null;
            boolean estimated = false;
            boolean inReading = false;
            boolean inType = false;
            int usagePoints = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw invalid();
                }
                if (event == XMLStreamConstants.START_ELEMENT && ATOM.equals(reader.getNamespaceURI())) {
                    if (root == null) {
                        root = reader.getLocalName();
                        if (!root.equals("feed")) {
                            throw invalid();
                        }
                    }
                    if (reader.getLocalName().equals("entry")) {
                        self = null;
                        up = null;
                        related = new ArrayList<>();
                    }
                    if (reader.getLocalName().equals("link")) {
                        String href = reader.getAttributeValue(null, "href");
                        String rel = reader.getAttributeValue(null, "rel");
                        if ("self".equals(rel)) self = href;
                        if ("up".equals(rel)) up = href;
                        if ("related".equals(rel) && href != null) related.add(href);
                    }
                }
                if ((event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT)
                        || !ESPI.equals(reader.getNamespaceURI())) {
                    continue;
                }
                String name = reader.getLocalName();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (name) {
                        case "UsagePoint" -> usagePoints++;
                        case "MeterReading" -> relations.put(self, List.copyOf(related));
                        case "ReadingType" -> {
                            inType = true;
                            multiplier = 0;
                            direction = 0;
                            unit = 0;
                        }
                        case "IntervalReading" -> {
                            inReading = true;
                            estimated = false;
                            value = null;
                            start = -1;
                            duration = 0;
                        }
                        case "uom" -> { if (inType) unit = Integer.parseInt(reader.getElementText()); }
                        case "flowDirection" -> { if (inType) direction = Integer.parseInt(reader.getElementText()); }
                        case "powerOfTenMultiplier" -> {
                            if (inType) multiplier = Integer.parseInt(reader.getElementText());
                        }
                        case "start" -> { if (inReading) start = Long.parseLong(reader.getElementText()); }
                        case "duration" -> { if (inReading) duration = Long.parseLong(reader.getElementText()); }
                        case "value" -> { if (inReading) value = new BigDecimal(reader.getElementText()); }
                        case "quality" -> {
                            if (inReading) {
                                int quality = Integer.parseInt(reader.getElementText());
                                if (quality == 8 || quality == 9) estimated = true;
                                if (quality == 10) throw invalid();
                            }
                        }
                        default -> { }
                    }
                } else if (name.equals("ReadingType")) {
                    inType = false;
                    if (unit == 72 && (direction == 1 || direction == 19) && multiplier >= -3 && multiplier <= 6) {
                        types.put(self, new Type(direction, multiplier));
                    }
                } else if (name.equals("IntervalReading")) {
                    inReading = false;
                    if (up == null || start < 0 || duration != 3600 || value == null || samples.size() >= 4000) {
                        throw invalid();
                    }
                    samples.add(new Sample(up, start, value, estimated));
                }
            }
            reader.close();
            if (root == null || usagePoints != 1 || samples.isEmpty()) {
                throw invalid();
            }
            var intervals = new TreeMap<Long, BigDecimal[]>();
            var estimatedHours = new HashSet<Long>();
            for (Sample sample : samples) {
                Type type = relations.getOrDefault(sample.meter(), List.of()).stream()
                        .filter(types::containsKey).map(types::get).findFirst().orElseThrow(GreenButton::invalid);
                BigDecimal[] interval = intervals.computeIfAbsent(sample.start(), unused -> new BigDecimal[2]);
                int channel = type.direction() == 1 ? 0 : 1;
                if (interval[channel] != null) {
                    throw invalid();
                }
                interval[channel] = sample.value().scaleByPowerOfTen(type.multiplier() - 3);
                if (sample.estimated()) estimatedHours.add(sample.start());
            }
            return intervals.entrySet().stream().map(item -> new MeteringService.Input(Instant.ofEpochSecond(item.getKey()),
                    orZero(item.getValue()[0]), orZero(item.getValue()[1]), null, null,
                    estimatedHours.contains(item.getKey()))).toList();
        } catch (XMLStreamException | IllegalArgumentException | java.time.DateTimeException error) {
            throw invalid();
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Supply an ESPI Atom feed for one meter using hourly imported or exported watt-hour readings.");
    }

    private record Type(int direction, int multiplier) {}
    private record Sample(String meter, long start, BigDecimal value, boolean estimated) {}
}
