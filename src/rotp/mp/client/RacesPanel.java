/*
 * Copyright 2015-2020 Ray Fowler
 *
 * Licensed under the GNU General Public License, Version 3 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rotp.mp.client;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;

import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.mp.protocol.PlayerView.EmpireDto;

/**
 * The Races (diplomacy) screen — the MOO-port "Races/Audience" surface, rendered
 * from PlayerView.empires and acting via the diploOffer / breakTreaty /
 * declareWar commands. One card per contacted empire shows the current
 * relationship and the actions the server will currently accept (offer
 * trade/peace/pact/alliance, break a treaty, declare war). The target's
 * diplomat AI answers offers immediately; verdicts arrive as diploReply and are
 * appended to the reply log at the bottom.
 *
 * DTO-driven, holds no game model — mirrors the eventual browser client. The
 * legality of each action lives in the pure {@link Diplomacy} helper.
 */
public class RacesPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final Consumer<Object> orderSender;
    private final JPanel cards = new JPanel();
    private final JTextArea replyLog = new JTextArea(6, 30);

    private PlayerView lastView;

    public RacesPanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 8));

        JLabel title = new JLabel("Races");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        add(title, BorderLayout.NORTH);

        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        add(new JScrollPane(cards), BorderLayout.CENTER);

        replyLog.setEditable(false);
        replyLog.setLineWrap(true);
        replyLog.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(replyLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Diplomatic replies"));
        add(logScroll, BorderLayout.SOUTH);

        showNoContacts();
    }

    /** rebuild the empire cards from a fresh view (diplomacy actions are one-shot,
     * so there is no local dirty state to preserve) */
    public void updateFromView(PlayerView view) {
        if (view == null)
            return;
        lastView = view;
        cards.removeAll();
        List<EmpireDto> others = Diplomacy.contacted(view);
        if (others.isEmpty())
            showNoContacts();
        else
            for (EmpireDto e : others)
                cards.add(buildCard(e));
        cards.revalidate();
        cards.repaint();
    }

    /** show the AI's answer to a diplomatic offer in the reply log */
    public void showReply(Messages.DiploReply dr) {
        if (dr == null)
            return;
        String name = empireName(dr.empireId);
        String verdict = dr.accepted ? "accepted" : "refused";
        String extra = ((dr.text == null) || dr.text.isEmpty()) ? "" : " - " + dr.text;
        replyLog.append(name + " " + verdict + " your " + dr.action + " offer" + extra + "\n");
        replyLog.setCaretPosition(replyLog.getDocument().getLength());
    }

    private void showNoContacts() {
        JLabel none = new JLabel("No other races contacted yet - send scouts to explore.");
        none.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        cards.add(none);
    }

    private JPanel buildCard(EmpireDto e) {
        JPanel card = new JPanel(new BorderLayout(6, 4));
        card.setBorder(BorderFactory.createTitledBorder(Diplomacy.displayName(e)));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        String disp = disposition(e);
        if (!disp.isEmpty()) {
            JLabel dl = new JLabel(disp);
            dl.setFont(dl.getFont().deriveFont(Font.ITALIC));
            dl.setAlignmentX(LEFT_ALIGNMENT);
            north.add(dl);
        }
        JLabel statusLabel = new JLabel(Diplomacy.statusLabel(e));
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        north.add(statusLabel);
        card.add(north, BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(0, 2, 6, 4));

        // offer trade, with a level spinner (1..maxTradeLevel)
        boolean canTrade = Diplomacy.canOfferTrade(e);
        int maxLevel = Math.max(1, e.maxTradeLevel);
        JSpinner tradeLevel = new JSpinner(new SpinnerNumberModel(maxLevel, 1, maxLevel, 1));
        tradeLevel.setEnabled(canTrade);
        JButton trade = new JButton("Offer Trade");
        trade.setEnabled(canTrade);
        trade.addActionListener(a -> offer(e.id, "TRADE", (Integer) tradeLevel.getValue()));
        JPanel tradeRow = new JPanel(new BorderLayout(4, 0));
        tradeRow.add(trade, BorderLayout.CENTER);
        tradeRow.add(tradeLevel, BorderLayout.EAST);
        actions.add(tradeRow);

        actions.add(actionButton("Offer Peace", Diplomacy.canOfferPeace(e), () -> offer(e.id, "PEACE", 0)));
        actions.add(actionButton("Offer Pact", Diplomacy.canOfferPact(e), () -> offer(e.id, "PACT", 0)));
        actions.add(actionButton("Offer Alliance", Diplomacy.canOfferAlliance(e), () -> offer(e.id, "ALLIANCE", 0)));

        // break an existing treaty (only the ones currently in force are listed)
        List<String> breakable = Diplomacy.breakableTreaties(e);
        JComboBox<String> treaty = new JComboBox<>(breakable.toArray(new String[0]));
        JButton breakBtn = new JButton("Break Treaty");
        boolean canBreak = !breakable.isEmpty();
        treaty.setEnabled(canBreak);
        breakBtn.setEnabled(canBreak);
        breakBtn.addActionListener(a -> {
            String t = (String) treaty.getSelectedItem();
            if (t != null)
                breakTreaty(e.id, t);
        });
        JPanel breakRow = new JPanel(new BorderLayout(4, 0));
        breakRow.add(breakBtn, BorderLayout.CENTER);
        breakRow.add(treaty, BorderLayout.EAST);
        actions.add(breakRow);

        actions.add(actionButton("Declare War", Diplomacy.canDeclareWar(e), () -> declareWar(e.id)));

        card.add(actions, BorderLayout.CENTER);

        // spy allocation + mission, and the intelligence report
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        JPanel spyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        spyRow.add(new JLabel("Spies " + e.spies + "/" + e.maxSpies + "  spend:"));
        int curSpend = Math.max(0, Math.min(20, e.spySpending));
        JSpinner spySpend = new JSpinner(new SpinnerNumberModel(curSpend, 0, 20, 1));
        spySpend.setToolTipText("Spy spending against this empire (0-20 ticks, each 0.5% of income)");
        spySpend.addChangeListener(a -> setSpySpending(e.id, (Integer) spySpend.getValue()));
        spyRow.add(spySpend);
        JComboBox<String> mission = new JComboBox<>(new String[]{"HIDE", "ESPIONAGE", "SABOTAGE"});
        mission.setSelectedItem((e.spyMission == null) ? "HIDE" : e.spyMission);
        mission.addActionListener(a -> setSpyMission(e.id, (String) mission.getSelectedItem()));
        spyRow.add(mission);
        // MOO1-style framing: if caught stealing tech from this empire, blame another
        JComboBox<FrameItem> frame = new JComboBox<>();
        frame.addItem(new FrameItem(-1, "Frame: none"));
        if (lastView != null)
            for (EmpireDto other : Diplomacy.contacted(lastView))
                if (other.id != e.id)
                    frame.addItem(new FrameItem(other.id, "Frame: " + Diplomacy.displayName(other)));
        selectFrame(frame, e.spyFrameEmpireId);
        frame.setToolTipText("If your spy is caught stealing tech from this empire, pin the "
            + "blame on the chosen empire (MOO1-style). 'None' = frame no one.");
        frame.addActionListener(a -> {
            FrameItem fi = (FrameItem) frame.getSelectedItem();
            if (fi != null) setSpyFrame(e.id, fi.id);
        });
        spyRow.add(frame);
        bottom.add(spyRow);

        JPanel reportRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton report = new JButton("Report");
        report.setToolTipText("Intelligence report on this race");
        report.addActionListener(a -> showReport(e));
        reportRow.add(report);
        // the rest of the MOO1 audience: technology exchange, gifts, threats. Each
        // opens on the server's menu for this empire (diploOptions -> techTradeMenu),
        // so the options offered are exactly the ones the server would accept.
        JButton audience = new JButton("Audience...");
        audience.setToolTipText("Exchange technology, offer aid, or threaten this race");
        audience.addActionListener(a -> requestDiploOptions(e.id));
        reportRow.add(audience);
        bottom.add(reportRow);

        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    /** the race's leader disposition, e.g. "Xenophobic Expansionist" (blank if unknown) */
    private static String disposition(EmpireDto e) {
        String p = (e.personality == null) ? "" : e.personality;
        String o = (e.objective == null) ? "" : e.objective;
        return (p + " " + o).trim();
    }

    private void setSpySpending(int empireId, int allocation) {
        Messages.SetSpySpending m = new Messages.SetSpySpending();
        m.empireId = empireId;
        m.allocation = allocation;
        orderSender.accept(m);
    }

    private void setSpyMission(int empireId, String missionName) {
        Messages.SetSpyMission m = new Messages.SetSpyMission();
        m.empireId = empireId;
        m.mission = missionName;
        orderSender.accept(m);
    }

    private void setSpyFrame(int spiedOnEmpireId, int frameEmpireId) {
        Messages.SetSpyFrame m = new Messages.SetSpyFrame();
        m.empireId = spiedOnEmpireId;
        m.frameEmpireId = frameEmpireId;
        orderSender.accept(m);
    }

    private static void selectFrame(JComboBox<FrameItem> combo, int frameEmpireId) {
        for (int i = 0; i < combo.getItemCount(); i++)
            if (combo.getItemAt(i).id == frameEmpireId) {
                combo.setSelectedIndex(i);
                return;
            }
    }

    /** a frame-target option: an empire to blame (id) with a display label */
    private static final class FrameItem {
        final int id;
        final String label;
        FrameItem(int id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }

    /** show the intelligence report on a race (what our spies have learned) */
    private void showReport(EmpireDto e) {
        String power = (e.relativePower <= 0f) ? "unknown"
            : String.format("%.0f%% of your strength", e.relativePower * 100);
        String age = (e.reportAge < 0) ? "never"
            : (e.reportAge == 0 ? "this turn" : e.reportAge + " turn(s) ago");
        String disp = disposition(e);
        String body = Diplomacy.displayName(e) + "\n\n"
            + (disp.isEmpty() ? "" : "Disposition: " + disp + "\n")
            + "Relations: " + Diplomacy.statusLabel(e) + "\n"
            + "Estimated strength: " + power + "\n"
            + "Technologies identified: " + e.knownTechCount + "\n"
            + "Spy network: " + e.spies + "/" + e.maxSpies + " spies, "
            + ((e.spyMission == null) ? "HIDE" : e.spyMission) + " mission\n"
            + "Last spy report: " + age;
        JOptionPane.showMessageDialog(this, body,
            "Race Report - " + Diplomacy.displayName(e), JOptionPane.INFORMATION_MESSAGE);
    }

    private JButton actionButton(String label, boolean enabled, Runnable action) {
        JButton b = new JButton(label);
        b.setEnabled(enabled);
        b.addActionListener(a -> action.run());
        return b;
    }

    private void offer(int empireId, String action, int tradeLevel) {
        Messages.DiploOffer o = new Messages.DiploOffer();
        o.empireId = empireId;
        o.action = action;
        o.tradeLevel = tradeLevel;
        orderSender.accept(o);
    }

    private void breakTreaty(int empireId, String treaty) {
        Messages.BreakTreaty b = new Messages.BreakTreaty();
        b.empireId = empireId;
        b.treaty = treaty;
        orderSender.accept(b);
    }

    private void declareWar(int empireId) {
        Messages.DeclareWar w = new Messages.DeclareWar();
        w.empireId = empireId;
        orderSender.accept(w);
    }

    // ---- audience: technology exchange, aid, threats (Phase 4) ----

    private void requestDiploOptions(int empireId) {
        Messages.DiploOptions m = new Messages.DiploOptions();
        m.empireId = empireId;
        orderSender.accept(m);
    }

    /**
     * The server's answer to diploOptions: everything this player may currently do
     * to that empire. Shown as a menu, mirroring the desktop audience screen.
     */
    public void showAudience(Messages.TechTradeMenu menu) {
        if (menu == null)
            return;
        String who = empireName(menu.empireId);
        List<String> choices = new ArrayList<>();
        if (menu.canExchangeTech && !menu.canRequest.isEmpty())
            choices.add("Exchange technology");
        if (menu.canOfferAid && !menu.aidAmounts.isEmpty())
            choices.add("Give money");
        if (menu.canOfferAid && !menu.canGift.isEmpty())
            choices.add("Give technology");
        if (!menu.jointWarTargets.isEmpty())
            choices.add("Propose a joint war");
        // the desktop audience wording (labels.txt DIPLOMACY_MENU_*)
        if (menu.canEvictSpies)
            choices.add("Remove All Spies (lowers relations)");
        if (menu.canThreatenSpying)
            choices.add("Stop Spying Activities");
        if (menu.canThreatenAttacking)
            choices.add("Stop Attacking");
        if (choices.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "There is nothing to discuss with " + who + " right now.",
                "Audience - " + who, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String pick = (String) JOptionPane.showInputDialog(this,
            "What do you wish to discuss with " + who + "?", "Audience - " + who,
            JOptionPane.PLAIN_MESSAGE, null, choices.toArray(new String[0]), choices.get(0));
        if (pick == null)
            return;
        switch (pick) {
            case "Exchange technology": askForTech(menu, who); break;
            case "Give money":          giveMoney(menu, who); break;
            case "Give technology":     giveTech(menu, who); break;
            case "Propose a joint war": proposeJointWar(menu, who); break;
            case "Remove All Spies (lowers relations)": threaten(menu.empireId, "EVICT_SPIES"); break;
            case "Stop Spying Activities":             threaten(menu.empireId, "STOP_SPYING"); break;
            case "Stop Attacking":                     threaten(menu.empireId, "STOP_ATTACKING"); break;
            default: break;
        }
    }

    private void askForTech(Messages.TechTradeMenu menu, String who) {
        Messages.TechOption t = pickTech(menu.canRequest,
            "Which technology do you want from " + who + "?", "Request Technology");
        if (t == null)
            return;
        Messages.RequestTech m = new Messages.RequestTech();
        m.empireId = menu.empireId;
        m.techId = t.id;
        orderSender.accept(m);   // they answer with a techCounterOffer, or refuse
    }

    /**
     * Their price for the tech you asked for: pick one of your technologies to give
     * up, or walk away. This is the second half of the exchange — a tech trade is a
     * negotiation, not a single order.
     */
    public void showCounterOffer(Messages.TechCounterOffer offer) {
        if (offer == null)
            return;
        String who = empireName(offer.empireId);
        if (offer.counterOptions.isEmpty()) {
            replyLog.append(who + " will not trade " + offer.requestedTechName + "\n");
            return;
        }
        String preamble = ((offer.text == null) || offer.text.isEmpty()) ? "" : offer.text + "\n\n";
        Messages.TechOption give = pickTech(offer.counterOptions,
            preamble + who + " will trade " + offer.requestedTechName
            + " for one of these. Which do you give up?",
            "Technology Exchange - " + who);
        if (give == null) {
            replyLog.append("You walked away from the exchange with " + who + "\n");
            return;
        }
        Messages.CounterOfferTech m = new Messages.CounterOfferTech();
        m.empireId = offer.empireId;
        m.requestedTechId = offer.requestedTechId;
        m.offeredTechId = give.id;
        orderSender.accept(m);
    }

    /**
     * Another human is asking you for a technology. Name one of theirs you want in
     * exchange, or refuse. Ignoring it refuses by default when the turn resolves.
     */
    public void promptIncomingTechRequest(Messages.Prompt prompt) {
        if (prompt == null)
            return;
        String who = empireName(prompt.empireId);
        String[] names = (prompt.choiceNames == null) ? new String[0] : prompt.choiceNames;
        if (names.length == 0)
            return;
        String pick = (String) JOptionPane.showInputDialog(this,
            who + " asks you for " + prompt.techName
            + ".\nWhich of their technologies do you want in exchange?",
            "Technology Request - " + who,
            JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        Messages.RespondTechRequest m = new Messages.RespondTechRequest();
        m.requestorId = prompt.empireId;
        if (pick != null)
            for (int i = 0; i < names.length; i++)
                if (names[i].equals(pick))
                    m.counterTechId = prompt.choiceIds[i];
        orderSender.accept(m);   // a null counterTechId refuses
        replyLog.append((m.counterTechId == null)
            ? "You refused " + who + "'s request for " + prompt.techName + "\n"
            : "You offered to trade " + prompt.techName + " to " + who + "\n");
    }

    private void giveMoney(Messages.TechTradeMenu menu, String who) {
        String[] amounts = new String[menu.aidAmounts.size()];
        for (int i = 0; i < amounts.length; i++)
            amounts[i] = menu.aidAmounts.get(i) + " BC";
        String pick = (String) JOptionPane.showInputDialog(this,
            "How much do you give " + who + "?", "Offer Aid",
            JOptionPane.PLAIN_MESSAGE, null, amounts, amounts[0]);
        if (pick == null)
            return;
        Messages.OfferAid m = new Messages.OfferAid();
        m.empireId = menu.empireId;
        m.amount = menu.aidAmounts.get(java.util.Arrays.asList(amounts).indexOf(pick));
        orderSender.accept(m);
    }

    private void giveTech(Messages.TechTradeMenu menu, String who) {
        Messages.TechOption t = pickTech(menu.canGift,
            "Which technology do you give " + who + "?", "Offer Technology");
        if (t == null)
            return;
        Messages.OfferAid m = new Messages.OfferAid();
        m.empireId = menu.empireId;
        m.techId = t.id;
        orderSender.accept(m);
    }

    /** ask them to join a war against a third empire */
    private void proposeJointWar(Messages.TechTradeMenu menu, String who) {
        String[] names = new String[menu.jointWarTargets.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = menu.jointWarTargets.get(i).name;
        String pick = (String) JOptionPane.showInputDialog(this,
            "Who should " + who + " declare war on?", "Joint War",
            JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (pick == null)
            return;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(pick)) {
                Messages.OfferJointWar m = new Messages.OfferJointWar();
                m.empireId = menu.empireId;
                m.targetId = menu.jointWarTargets.get(i).id;
                orderSender.accept(m);
                return;
            }
        }
    }

    /**
     * They will join the war, for a price. Techs and BC leave your empire if you
     * agree; walking away costs nothing but the offer lapses with the turn.
     */
    public void showJointWarCounter(Messages.JointWarCounter c) {
        if (c == null)
            return;
        String who = empireName(c.empireId);
        StringBuilder price = new StringBuilder();
        for (Messages.TechOption t : c.techs)
            price.append("\n  ").append(t.name);
        if (c.bribe > 0)
            price.append("\n  ").append(c.bribe).append(" BC");
        if (price.length() == 0)
            price.append("\n  (nothing)");
        String preamble = ((c.text == null) || c.text.isEmpty()) ? "" : c.text + "\n\n";
        int pick = JOptionPane.showConfirmDialog(this,
            preamble + who + " will join the war against " + empireName(c.targetId)
            + " in exchange for:" + price + "\n\nAgree?",
            "Joint War - " + who, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (pick != JOptionPane.YES_OPTION) {
            replyLog.append("You declined " + who + "'s price for the war\n");
            return;
        }
        Messages.AcceptJointWarCounter a = new Messages.AcceptJointWarCounter();
        a.empireId = c.empireId;
        a.targetId = c.targetId;
        orderSender.accept(a);
    }

    private void threaten(int empireId, String threat) {
        Messages.Threaten m = new Messages.Threaten();
        m.empireId = empireId;
        m.threat = threat;
        orderSender.accept(m);
    }

    /** a chooser over technologies, labelled the way the desktop trade menus are
     * (name, tier and research cost — the rough worth of the deal) */
    private Messages.TechOption pickTech(List<Messages.TechOption> options, String message, String title) {
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) {
            Messages.TechOption o = options.get(i);
            labels[i] = o.name + "  (tier " + o.quintile + ", " + o.cost + " RP)";
        }
        String pick = (String) JOptionPane.showInputDialog(this, message, title,
            JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (pick == null)
            return null;
        return options.get(java.util.Arrays.asList(labels).indexOf(pick));
    }

    private String empireName(int empireId) {
        if ((lastView != null) && (lastView.empires != null))
            for (EmpireDto e : lastView.empires)
                if (e.id == empireId)
                    return Diplomacy.displayName(e);
        return "Empire " + empireId;
    }
}
