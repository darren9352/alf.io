/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.controller.form.UpdateTicketOwnerForm;
import alfio.manager.plugin.PluginManager;
import alfio.manager.support.CategoryEvaluator;
import alfio.manager.support.FeeCalculator;
import alfio.manager.support.PartialTicketTextGenerator;
import alfio.manager.support.PaymentResult;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.system.Mailer;
import alfio.model.*;
import alfio.model.AdditionalServiceItem.AdditionalServiceItemStatus;
import alfio.model.PromoCodeDiscount.DiscountType;
import alfio.model.SpecialPrice.Status;
import alfio.model.Ticket.TicketStatus;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.model.decorator.AdditionalServiceItemPriceContainer;
import alfio.model.decorator.AdditionalServicePriceContainer;
import alfio.model.decorator.TicketPriceContainer;
import alfio.model.modification.ASReservationWithOptionalCodeModification;
import alfio.model.modification.AdditionalServiceReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.PaymentProxy;
import alfio.model.user.Organization;
import alfio.model.user.Role;
import alfio.repository.*;
import alfio.repository.user.OrganizationRepository;
import alfio.repository.user.UserRepository;
import alfio.util.*;
import de.danielbechler.diff.ObjectDifferBuilder;
import de.danielbechler.diff.node.DiffNode;
import de.danielbechler.diff.node.Visit;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.context.MessageSource;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static alfio.model.TicketReservation.TicketReservationStatus.IN_PAYMENT;
import static alfio.model.TicketReservation.TicketReservationStatus.OFFLINE_PAYMENT;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.util.MonetaryUtil.formatCents;
import static alfio.util.MonetaryUtil.unitToCents;
import static alfio.util.OptionalWrapper.optionally;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.*;
import static org.apache.commons.lang3.time.DateUtils.addHours;
import static org.apache.commons.lang3.time.DateUtils.truncate;

@Component
@Transactional
@Log4j2
public class TicketReservationManager {
    
    private static final String STUCK_TICKETS_MSG = "there are stuck tickets for the event %s. Please check admin area.";
    private static final String STUCK_TICKETS_SUBJECT = "warning: stuck tickets found";
    static final String NOT_YET_PAID_TRANSACTION_ID = "not-paid";

    private final EventRepository eventRepository;
    private final OrganizationRepository organizationRepository;
    private final TicketRepository ticketRepository;
    private final TicketReservationRepository ticketReservationRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository;
    private final ConfigurationManager configurationManager;
    private final PaymentManager paymentManager;
    private final PromoCodeDiscountRepository promoCodeDiscountRepository;
    private final SpecialPriceRepository specialPriceRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationManager notificationManager;
    private final MessageSource messageSource;
    private final TemplateManager templateManager;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final WaitingQueueManager waitingQueueManager;
    private final PluginManager pluginManager;
    private final TicketFieldRepository ticketFieldRepository;
    private final AdditionalServiceRepository additionalServiceRepository;
    private final AdditionalServiceItemRepository additionalServiceItemRepository;
    private final AdditionalServiceTextRepository additionalServiceTextRepository;
    private final InvoiceSequencesRepository invoiceSequencesRepository;
    private final AuditingRepository auditingRepository;
    private final UserRepository userRepository;
    private final ExtensionManager extensionManager;

    public static class NotEnoughTicketsException extends RuntimeException {

    }

    public static class MissingSpecialPriceTokenException extends RuntimeException {
    }

    public static class InvalidSpecialPriceTokenException extends RuntimeException {

    }

    public static class OfflinePaymentException extends RuntimeException {
        OfflinePaymentException(String message){ super(message); }
    }

    public TicketReservationManager(EventRepository eventRepository,
                                    OrganizationRepository organizationRepository,
                                    TicketRepository ticketRepository,
                                    TicketReservationRepository ticketReservationRepository,
                                    TicketCategoryRepository ticketCategoryRepository,
                                    TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository,
                                    ConfigurationManager configurationManager,
                                    PaymentManager paymentManager,
                                    PromoCodeDiscountRepository promoCodeDiscountRepository,
                                    SpecialPriceRepository specialPriceRepository,
                                    TransactionRepository transactionRepository,
                                    NotificationManager notificationManager,
                                    MessageSource messageSource,
                                    TemplateManager templateManager,
                                    PlatformTransactionManager transactionManager,
                                    WaitingQueueManager waitingQueueManager,
                                    PluginManager pluginManager,
                                    TicketFieldRepository ticketFieldRepository,
                                    AdditionalServiceRepository additionalServiceRepository,
                                    AdditionalServiceItemRepository additionalServiceItemRepository,
                                    AdditionalServiceTextRepository additionalServiceTextRepository,
                                    InvoiceSequencesRepository invoiceSequencesRepository,
                                    AuditingRepository auditingRepository,
                                    UserRepository userRepository,
                                    ExtensionManager extensionManager) {
        this.eventRepository = eventRepository;
        this.organizationRepository = organizationRepository;
        this.ticketRepository = ticketRepository;
        this.ticketReservationRepository = ticketReservationRepository;
        this.ticketCategoryRepository = ticketCategoryRepository;
        this.ticketCategoryDescriptionRepository = ticketCategoryDescriptionRepository;
        this.configurationManager = configurationManager;
        this.paymentManager = paymentManager;
        this.promoCodeDiscountRepository = promoCodeDiscountRepository;
        this.specialPriceRepository = specialPriceRepository;
        this.transactionRepository = transactionRepository;
        this.notificationManager = notificationManager;
        this.messageSource = messageSource;
        this.templateManager = templateManager;
        this.waitingQueueManager = waitingQueueManager;
        this.pluginManager = pluginManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager, new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        this.ticketFieldRepository = ticketFieldRepository;
        this.additionalServiceRepository = additionalServiceRepository;
        this.additionalServiceItemRepository = additionalServiceItemRepository;
        this.additionalServiceTextRepository = additionalServiceTextRepository;
        this.invoiceSequencesRepository = invoiceSequencesRepository;
        this.auditingRepository = auditingRepository;
        this.userRepository = userRepository;
        this.extensionManager = extensionManager;
    }
    
    /**
     * Create a ticket reservation. It will create a reservation _only_ if it can find enough tickets. Note that it will not do date/validity validation. This must be ensured by the
     * caller.
     *
     * @param event
     * @param list
     * @param reservationExpiration
     * @param forWaitingQueue
     * @return
     */
    public String createTicketReservation(Event event,
                                          List<TicketReservationWithOptionalCodeModification> list,
                                          List<ASReservationWithOptionalCodeModification> additionalServices,
                                          Date reservationExpiration,
                                          Optional<String> specialPriceSessionId,
                                          Optional<String> promotionCodeDiscount,
                                          Locale locale,
                                          boolean forWaitingQueue) throws NotEnoughTicketsException, MissingSpecialPriceTokenException, InvalidSpecialPriceTokenException {
        String reservationId = UUID.randomUUID().toString();
        
        Optional<PromoCodeDiscount> discount = promotionCodeDiscount.flatMap((promoCodeDiscount) -> getPromoCodeDiscountRepository().findPromoCodeInEventOrOrganization(event.getId(), promoCodeDiscount));
        
        getTicketReservationRepository().createNewReservation(reservationId, reservationExpiration, discount.map(PromoCodeDiscount::getId).orElse(null), locale.getLanguage(), event.getId(), event.getVat(), event.isVatIncluded());
        list.forEach(t -> reserveTicketsForCategory(event, specialPriceSessionId, reservationId, t, locale, forWaitingQueue, discount.orElse(null)));

        int ticketCount = list
            .stream()
            .map(TicketReservationWithOptionalCodeModification::getAmount)
            .mapToInt(Integer::intValue).sum();

        // apply valid additional service with supplement policy mandatory one for ticket
        getAdditionalServiceRepository().findAllInEventWithPolicy(event.getId(), AdditionalService.SupplementPolicy.MANDATORY_ONE_FOR_TICKET)
            .stream()
            .filter(AdditionalService::getSaleable)
            .forEach(as -> {
                AdditionalServiceReservationModification asrm = new AdditionalServiceReservationModification();
                asrm.setAdditionalServiceId(as.getId());
                asrm.setQuantity(ticketCount);
                reserveAdditionalServicesForReservation(event.getId(), reservationId, new ASReservationWithOptionalCodeModification(asrm, Optional.empty()), discount.orElse(null));
        });

        additionalServices.forEach(as -> reserveAdditionalServicesForReservation(event.getId(), reservationId, as, discount.orElse(null)));

        TicketReservation reservation = getTicketReservationRepository().findReservationById(reservationId);

        OrderSummary orderSummary = orderSummaryForReservationId(reservation.getId(), event, Locale.forLanguageTag(reservation.getUserLanguage()));
        getTicketReservationRepository().addReservationInvoiceOrReceiptModel(reservationId, Json.toJson(orderSummary));

        getAuditingRepository().insert(reservationId, null, event.getId(), Audit.EventType.RESERVATION_CREATE, new Date(), Audit.EntityType.RESERVATION, reservationId);

        return reservationId;
    }

    private AuditingRepository getAuditingRepository() {
		return auditingRepository;
	}

    private AdditionalServiceRepository getAdditionalServiceRepository() {
		return additionalServiceRepository;
	}

	private PromoCodeDiscountRepository getPromoCodeDiscountRepository() {
		return promoCodeDiscountRepository;
	}

	private TicketReservationRepository getTicketReservationRepository() {
		return ticketReservationRepository;
	}

	public Pair<List<TicketReservation>, Integer> findAllReservationsInEvent(int eventId, Integer page, String search, List<TicketReservationStatus> status) {
        final int pageSize = 50;
        int offset = page == null ? 0 : page * pageSize;
        String toSearch = StringUtils.trimToNull(search);
        toSearch = toSearch == null ? null : ("%" + toSearch + "%");
        List<String> toFilter = (status == null || status.isEmpty() ? Arrays.asList(TicketReservationStatus.values()) : status).stream().map(TicketReservationStatus::toString).collect(toList());
        return Pair.of(getTicketReservationRepository().findAllReservationsInEvent(eventId, offset, pageSize, toSearch, toFilter), getTicketReservationRepository().countAllReservationsInEvent(eventId, toSearch, toFilter));
    }

    void reserveTicketsForCategory(Event event, Optional<String> specialPriceSessionId, String transactionId, TicketReservationWithOptionalCodeModification ticketReservation, Locale locale, boolean forWaitingQueue, PromoCodeDiscount discount) {
        //first check if there is another pending special price token bound to the current sessionId
        Optional<SpecialPrice> specialPrice = fixToken(ticketReservation.getSpecialPrice(), ticketReservation.getTicketCategoryId(), event.getId(), specialPriceSessionId, ticketReservation);

        List<Integer> reservedForUpdate = reserveTickets(event.getId(), ticketReservation, forWaitingQueue ? asList(TicketStatus.RELEASED, TicketStatus.PRE_RESERVED) : singletonList(TicketStatus.FREE));
        int requested = ticketReservation.getAmount();
        if (reservedForUpdate.size() != requested) {
            throw new NotEnoughTicketsException();
        }

        TicketCategory category = getTicketCategoryRepository().getByIdAndActive(ticketReservation.getTicketCategoryId(), event.getId());
        if (specialPrice.isPresent()) {
            if(reservedForUpdate.size() != 1) {
                throw new NotEnoughTicketsException();
            }
            SpecialPrice sp = specialPrice.get();
            getTicketRepository().reserveTicket(transactionId, reservedForUpdate.stream().findFirst().orElseThrow(IllegalStateException::new),sp.getId(), locale.getLanguage(), category.getSrcPriceCts());
            getSpecialPriceRepository().updateStatus(sp.getId(), Status.PENDING.toString(), sp.getSessionIdentifier());
        } else {
            getTicketRepository().reserveTickets(transactionId, reservedForUpdate, ticketReservation.getTicketCategoryId(), locale.getLanguage(), category.getSrcPriceCts());
        }
        Ticket ticket = getTicketRepository().findById(reservedForUpdate.get(0), category.getId());
        TicketPriceContainer priceContainer = TicketPriceContainer.from(ticket, null, event, discount);
        getTicketRepository().updateTicketPrice(reservedForUpdate, category.getId(), event.getId(), category.getSrcPriceCts(), MonetaryUtil.unitToCents(priceContainer.getFinalPrice()), MonetaryUtil.unitToCents(priceContainer.getVAT()), MonetaryUtil.unitToCents(priceContainer.getAppliedDiscount()));
    }

    private SpecialPriceRepository getSpecialPriceRepository() {
		return specialPriceRepository;
	}

	private TicketRepository getTicketRepository() {
		return ticketRepository;
	}

	private TicketCategoryRepository getTicketCategoryRepository() {
		return ticketCategoryRepository;
	}

	private void reserveAdditionalServicesForReservation(int eventId, String transactionId, ASReservationWithOptionalCodeModification additionalServiceReservation, PromoCodeDiscount discount) {
        Optional.ofNullable(additionalServiceReservation.getAdditionalServiceId())
            .flatMap(id -> optionally(() -> getAdditionalServiceRepository().getById(id, eventId)))
            .filter(as -> additionalServiceReservation.getQuantity() > 0 && (as.isFixPrice() || Optional.ofNullable(additionalServiceReservation.getAmount()).filter(a -> a.compareTo(BigDecimal.ZERO) > 0).isPresent()))
            .map(as -> Pair.of(getEventRepository().findById(eventId), as))
            .ifPresent(pair -> {
                Event e = pair.getKey();
                AdditionalService as = pair.getValue();
                IntStream.range(0, additionalServiceReservation.getQuantity())
                    .forEach(i -> {
                        AdditionalServicePriceContainer pc = AdditionalServicePriceContainer.from(additionalServiceReservation.getAmount(), as, e, discount);
                        getAdditionalServiceItemRepository().insert(UUID.randomUUID().toString(), ZonedDateTime.now(Clock.systemUTC()), transactionId,
                            as.getId(), AdditionalServiceItemStatus.PENDING, eventId, pc.getSrcPriceCts(), unitToCents(pc.getFinalPrice()), unitToCents(pc.getVAT()), unitToCents(pc.getAppliedDiscount()));
                    });
            });

    }

    private AdditionalServiceItemRepository getAdditionalServiceItemRepository() {
		return additionalServiceItemRepository;
	}

	private EventRepository getEventRepository() {
		return eventRepository;
	}

	List<Integer> reserveTickets(int eventId, TicketReservationWithOptionalCodeModification ticketReservation, List<TicketStatus> requiredStatuses) {
        return reserveTickets(eventId, ticketReservation.getTicketCategoryId(), ticketReservation.getAmount(), requiredStatuses);
    }

    List<Integer> reserveTickets(int eventId , int categoryId, int qty, List<TicketStatus> requiredStatuses) {
        TicketCategory category = getTicketCategoryRepository().getByIdAndActive(categoryId, eventId);
        List<String> statusesAsString = requiredStatuses.stream().map(TicketStatus::name).collect(toList());
        if(category.isBounded()) {
            return getTicketRepository().selectTicketInCategoryForUpdate(eventId, categoryId, qty, statusesAsString);
        }
        return getTicketRepository().selectNotAllocatedTicketsForUpdate(eventId, qty, statusesAsString);
    }

    Optional<SpecialPrice> fixToken(Optional<SpecialPrice> token, int ticketCategoryId, int eventId, Optional<String> specialPriceSessionId, TicketReservationWithOptionalCodeModification ticketReservation) {

        TicketCategory ticketCategory = getTicketCategoryRepository().getByIdAndActive(ticketCategoryId, eventId);
        if(!ticketCategory.isAccessRestricted()) {
            return Optional.empty();
        }

        Optional<SpecialPrice> specialPrice = renewSpecialPrice(token, specialPriceSessionId);

        if(token.isPresent() && !specialPrice.isPresent()) {
            //there is a special price in the request but this isn't valid anymore
            throw new InvalidSpecialPriceTokenException();
        }

        boolean canAccessRestrictedCategory = specialPrice.isPresent()
                && specialPrice.get().getStatus() == SpecialPrice.Status.FREE
                && specialPrice.get().getTicketCategoryId() == ticketCategoryId;


        if (canAccessRestrictedCategory && ticketReservation.getAmount() > 1) {
            throw new NotEnoughTicketsException();
        }

        if (!canAccessRestrictedCategory && ticketCategory.isAccessRestricted()) {
            throw new MissingSpecialPriceTokenException();
        }

        return specialPrice;
    }

    public PaymentResult confirm(String gatewayToken, String payerId, Event event, String reservationId,
                                 String email, CustomerName customerName, Locale userLanguage, String billingAddress,
                                 TotalPrice reservationCost, Optional<String> specialPriceSessionId, Optional<PaymentProxy> method,
                                 boolean invoiceRequested, String vatCountryCode, String vatNr, PriceContainer.VatStatus vatStatus) {
        PaymentProxy paymentProxy = evaluatePaymentProxy(method, reservationCost);
        if(!initPaymentProcess(reservationCost, paymentProxy, reservationId, email, customerName, userLanguage, billingAddress)) {
            return PaymentResult.unsuccessful("error.STEP2_UNABLE_TO_TRANSITION");
        }
        try {
            PaymentResult paymentResult;
            getTicketReservationRepository().lockReservationForUpdate(reservationId);
            if(reservationCost.getPriceWithVAT() > 0) {
            	
                updateInvoice(event, reservationId, invoiceRequested);
                getTicketReservationRepository().updateBillingData(vatStatus, vatNr, vatCountryCode, invoiceRequested, reservationId);

                //
                getExtensionManager().handleInvoiceGeneration(event, reservationId,
                    email, customerName, userLanguage, billingAddress,
                    reservationCost, invoiceRequested, vatCountryCode, vatNr, vatStatus).ifPresent(invoiceGeneration -> {
                    if (invoiceGeneration.getInvoiceNumber() != null) {
                    	getTicketReservationRepository().setInvoiceNumber(reservationId, invoiceGeneration.getInvoiceNumber());
                    }
                });

                //
                paymentResult = resultFor(gatewayToken, payerId, event, reservationId, email, customerName,
						billingAddress, reservationCost, paymentProxy);
                
                if (paymentProxy == PaymentProxy.STRIPE || paymentProxy == PaymentProxy.PAYPAL) {
                	return paymentResult;
                }

            } else {
                paymentResult = PaymentResult.successful(NOT_YET_PAID_TRANSACTION_ID);
            }
            completeReservation(event.getId(), reservationId, email, customerName, userLanguage, billingAddress, specialPriceSessionId, paymentProxy);
            return paymentResult;
        } catch(Exception ex) {
            //it is guaranteed that in this case we're dealing with "local" error (e.g. database failure),
            //thus it is safer to not rollback the reservation status
            log.error("unexpected error during payment confirmation", ex);
            return PaymentResult.unsuccessful("error.STEP2_STRIPE_unexpected");
        }

    }

	private PaymentResult resultFor(String gatewayToken, String payerId, Event event, String reservationId,
			String email, CustomerName customerName, String billingAddress, TotalPrice reservationCost,
			PaymentProxy paymentProxy) {
		PaymentResult paymentResult;
		switch(paymentProxy) {
		    case STRIPE:
		        paymentResult = getPaymentManager().processStripePayment(reservationId, gatewayToken, reservationCost.getPriceWithVAT(), event, email, customerName, billingAddress);
		        if(!paymentResult.isSuccessful()) {
		            reTransitionToPending(reservationId);
		        }
		        break;
		    case PAYPAL:
		        paymentResult = getPaymentManager().processPayPalPayment(reservationId, gatewayToken, payerId, reservationCost.getPriceWithVAT(), event);
		        if(!paymentResult.isSuccessful()) {
		            reTransitionToPending(reservationId);
		        }
		        break;
		    case OFFLINE:
		        transitionToOfflinePayment(event, reservationId, email, customerName, billingAddress);
		        paymentResult = PaymentResult.successful(NOT_YET_PAID_TRANSACTION_ID);
		        break;
		    case ON_SITE:
		        paymentResult = PaymentResult.successful(NOT_YET_PAID_TRANSACTION_ID);
		        break;
		    default:
		        throw new IllegalArgumentException("Payment proxy "+paymentProxy+ " not recognized");
		}
		return paymentResult;
	}

	private void updateInvoice(Event event, String reservationId, boolean invoiceRequested) {
		if(invoiceRequested && getConfigurationManager().hasAllConfigurationsForInvoice(event)) {
		    int invoiceSequence = getInvoiceSequencesRepository().lockReservationForUpdate(event.getOrganizationId());
		    getInvoiceSequencesRepository().incrementSequenceFor(event.getOrganizationId());
		    String pattern = getConfigurationManager().getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.INVOICE_NUMBER_PATTERN), "%d");
		    getTicketReservationRepository().setInvoiceNumber(reservationId, String.format(pattern, invoiceSequence));
		}
	}

    private PaymentManager getPaymentManager() {
		return paymentManager;
	}

	private ExtensionManager getExtensionManager() {
		return extensionManager;
	}

	private InvoiceSequencesRepository getInvoiceSequencesRepository() {
		return invoiceSequencesRepository;
	}

	private ConfigurationManager getConfigurationManager() {
		return configurationManager;
	}

	private PaymentProxy evaluatePaymentProxy(Optional<PaymentProxy> method, TotalPrice reservationCost) {
        if(method.isPresent()) {
            return method.get();
        }
        if(reservationCost.getPriceWithVAT() == 0) {
            return PaymentProxy.NONE;
        }
        return PaymentProxy.STRIPE;
    }

    private boolean initPaymentProcess(TotalPrice reservationCost, PaymentProxy paymentProxy, String reservationId, String email, CustomerName customerName, Locale userLanguage, String billingAddress) {
        if(reservationCost.getPriceWithVAT() > 0 && paymentProxy == PaymentProxy.STRIPE) {
            try {
                transitionToInPayment(reservationId, email, customerName, userLanguage, billingAddress);
            } catch (Exception e) {
                //unable to do the transition. Exiting.
                log.debug(String.format("unable to flag the reservation %s as IN_PAYMENT", reservationId), e);
                return false;
            }
        }
        return true;
    }

    public void confirmOfflinePayment(Event event, String reservationId, String username) {
        TicketReservation ticketReservation = findById(reservationId).orElseThrow(IllegalArgumentException::new);
        getTicketReservationRepository().lockReservationForUpdate(reservationId);
        Validate.isTrue(ticketReservation.getPaymentMethod() == PaymentProxy.OFFLINE, "invalid payment method");
        Validate.isTrue(ticketReservation.getStatus() == TicketReservationStatus.OFFLINE_PAYMENT, "invalid status");


        getTicketReservationRepository().confirmOfflinePayment(reservationId, TicketReservationStatus.COMPLETE.name(), ZonedDateTime.now(event.getZoneId()));

        registerAlfioTransaction(event, reservationId, PaymentProxy.OFFLINE);

        getAuditingRepository().insert(reservationId, userRepository.findIdByUserName(username).orElse(null), event.getId(), Audit.EventType.RESERVATION_OFFLINE_PAYMENT_CONFIRMED, new Date(), Audit.EntityType.RESERVATION, ticketReservation.getId());

        CustomerName customerName = new CustomerName(ticketReservation.getFullName(), ticketReservation.getFirstName(), ticketReservation.getLastName(), event);
        acquireItems(TicketStatus.ACQUIRED, AdditionalServiceItemStatus.ACQUIRED, PaymentProxy.OFFLINE, reservationId, ticketReservation.getEmail(), customerName, ticketReservation.getUserLanguage(), ticketReservation.getBillingAddress(), event.getId());

        Locale language = findReservationLanguage(reservationId);

        sendConfirmationEmail(event, findById(reservationId).orElseThrow(IllegalArgumentException::new), language);

        final TicketReservation finalReservation = getTicketReservationRepository().findReservationById(reservationId);
        getPluginManager().handleReservationConfirmation(finalReservation, event.getId());
        getExtensionManager().handleReservationConfirmation(finalReservation, event.getId());
    }

    private PluginManager getPluginManager() {
		return pluginManager;
	}

	void registerAlfioTransaction(Event event, String reservationId, PaymentProxy paymentProxy) {
        int priceWithVAT = totalReservationCostWithVAT(reservationId).getPriceWithVAT();
        Long platformFee = FeeCalculator.getCalculator(event, getConfigurationManager())
            .apply(getTicketRepository().countTicketsInReservation(reservationId), (long) priceWithVAT)
            .orElse(0L);

        //FIXME we must support multiple transactions for a reservation, otherwise we can't handle properly the case of ON_SITE payments

        if(paymentProxy != PaymentProxy.ON_SITE || !getTransactionRepository().loadOptionalByReservationId(reservationId).isPresent()) {
            String transactionId = paymentProxy.getKey() + "-" + System.currentTimeMillis();
            getTransactionRepository().insert(transactionId, null, reservationId, ZonedDateTime.now(event.getZoneId()),
                priceWithVAT, event.getCurrency(), "Offline payment confirmed for "+reservationId, paymentProxy.getKey(), platformFee, 0L);
        } else {
            log.warn("ON-Site check-in: ignoring transaction registration for reservationId {}", reservationId);
        }

    }


    private TransactionRepository getTransactionRepository() {
		return transactionRepository;
	}

	public void sendConfirmationEmail(Event event, TicketReservation ticketReservation, Locale language) {
        String reservationId = ticketReservation.getId();

        OrderSummary summary = orderSummaryForReservationId(reservationId, event, language);

        Map<String, Object> reservationEmailModel = prepareModelForReservationEmail(event, ticketReservation);
        List<Mailer.Attachment> attachments = new ArrayList<>(1);
        if(!summary.getNotYetPaid() && !summary.getFree()) {
            Map<String, String> model = new HashMap<>();
            model.put("reservationId", reservationId);
            model.put("eventId", Integer.toString(event.getId()));
            model.put("language", Json.toJson(language));
            model.put("reservationEmailModel", Json.toJson(reservationEmailModel));
            attachMailInvoiceOrReceipt(ticketReservation, attachments, model);

        }

        getNotificationManager().sendSimpleEmail(event, ticketReservation.getEmail(), getMessageSource().getMessage("reservation-email-subject",
                new Object[]{getShortReservationID(event, reservationId), event.getDisplayName()}, language),
            () -> getTemplateManager().renderTemplate(event, TemplateResource.CONFIRMATION_EMAIL, reservationEmailModel, language), attachments);
    }

	private void attachMailInvoiceOrReceipt(TicketReservation ticketReservation, List<Mailer.Attachment> attachments,
			Map<String, String> model) {
		if(ticketReservation.getHasInvoiceNumber()) {
		    attachments.add(new Mailer.Attachment("invoice.pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.INVOICE_PDF));
		} else {
		    attachments.add(new Mailer.Attachment("receipt.pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.RECEIPT_PDF));
		}
	}

    private TemplateManager getTemplateManager() {
		return templateManager;
	}

	private MessageSource getMessageSource() {
		return messageSource;
	}

	private NotificationManager getNotificationManager() {
		return notificationManager;
	}

	private Locale findReservationLanguage(String reservationId) {
        return ticketReservationRepository.findOptionalReservationById(reservationId).map(TicketReservation::getUserLanguage).map(Locale::forLanguageTag).orElse(Locale.ENGLISH);
    }

    public void deleteOfflinePayment(Event event, String reservationId, boolean expired) {
        TicketReservation reservation = findById(reservationId).orElseThrow(IllegalArgumentException::new);
        Validate.isTrue(reservation.getStatus() == OFFLINE_PAYMENT, "Invalid reservation status");
        Map<String, Object> emailModel = prepareModelForReservationEmail(event, reservation);
        Locale reservationLanguage = findReservationLanguage(reservationId);
        String subject = getMessageSource().getMessage("reservation-email-expired-subject", new Object[]{getShortReservationID(event, reservationId), event.getDisplayName()}, reservationLanguage);
        cancelReservation(reservationId, expired);
        getNotificationManager().sendSimpleEmail(event, reservation.getEmail(), subject,
            () ->  getTemplateManager().renderTemplate(event, TemplateResource.OFFLINE_RESERVATION_EXPIRED_EMAIL, emailModel, reservationLanguage)
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepareModelForReservationEmail(Event event, TicketReservation reservation, Optional<String> vat, OrderSummary summary) {
        Organization organization = getOrganizationRepository().getById(event.getOrganizationId());
        List<Ticket> tickets = findTicketsInReservation(reservation.getId());
        String reservationUrl = reservationUrl(reservation.getId());
        String reservationShortID = getShortReservationID(event, reservation.getId());
        Optional<String> invoiceAddress = getConfigurationManager().getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.INVOICE_ADDRESS));
        Optional<String> bankAccountNr = getConfigurationManager().getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BANK_ACCOUNT_NR));
        Optional<String> bankAccountOwner = getConfigurationManager().getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BANK_ACCOUNT_OWNER));
        return TemplateResource.prepareModelForConfirmationEmail(organization, event, reservation, vat, tickets, summary, reservationUrl, reservationShortID, invoiceAddress, bankAccountNr, bankAccountOwner);
    }

    private OrganizationRepository getOrganizationRepository() {
		return organizationRepository;
	}

	@Transactional(readOnly = true)
    public Map<String, Object> prepareModelForReservationEmail(Event event, TicketReservation reservation) {
        Optional<String> vat = getVAT(event);
        OrderSummary summary = orderSummaryForReservationId(reservation.getId(), event, Locale.forLanguageTag(reservation.getUserLanguage()));
        return prepareModelForReservationEmail(event, reservation, vat, summary);
    }

    private void transitionToInPayment(String reservationId, String email, CustomerName customerName, Locale userLanguage, String billingAddress) {
        getRequiresNewTransactionTemplate().execute(status -> {
            int updatedReservation = getTicketReservationRepository().updateTicketReservation(reservationId, IN_PAYMENT.toString(), email,
                customerName.getFullName(), customerName.getFirstName(), customerName.getLastName(), userLanguage.getLanguage(), billingAddress, null, PaymentProxy.STRIPE.toString());
            Validate.isTrue(updatedReservation == 1, "expected exactly one updated reservation, got " + updatedReservation);
            return null;
        });
    }

    private TransactionTemplate getRequiresNewTransactionTemplate() {
		return requiresNewTransactionTemplate;
	}

	private void transitionToOfflinePayment(Event event, String reservationId, String email, CustomerName customerName, String billingAddress) {
        ZonedDateTime deadline = getOfflinePaymentDeadline(event, getConfigurationManager());
        int updatedReservation = getTicketReservationRepository().postponePayment(reservationId, Date.from(deadline.toInstant()), email,
            customerName.getFullName(), customerName.getFirstName(), customerName.getLastName(), billingAddress);
        Validate.isTrue(updatedReservation == 1, "expected exactly one updated reservation, got " + updatedReservation);
    }

    public static ZonedDateTime getOfflinePaymentDeadline(Event event, ConfigurationManager configurationManager) {
        ZonedDateTime now = ZonedDateTime.now(event.getZoneId());
        int waitingPeriod = getOfflinePaymentWaitingPeriod(event, configurationManager);
        if(waitingPeriod == 0) {
            log.warn("accepting offline payments the same day is a very bad practice and should be avoided. Please set cash payment as payment method next time");
            //if today is the event start date, then we add a couple of hours.
            //TODO Maybe should we avoid this wrong behavior upfront, in the admin area?
            return now.plusHours(2);
        }
        return now.plusDays(waitingPeriod).truncatedTo(ChronoUnit.HALF_DAYS);
    }

    public static int getOfflinePaymentWaitingPeriod(Event event, ConfigurationManager configurationManager) {
        ZonedDateTime now = ZonedDateTime.now(event.getZoneId());
        ZonedDateTime eventBegin = event.getBegin();
        int daysToBegin = (int) ChronoUnit.DAYS.between(now.toLocalDate(), eventBegin.toLocalDate());
        if (daysToBegin < 0) {
            throw new OfflinePaymentException("Cannot confirm an offline reservation after event start");
        }
        int waitingPeriod = configurationManager.getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), OFFLINE_PAYMENT_DAYS), 5);
        return Math.min(daysToBegin, waitingPeriod);
    }

    public static boolean hasValidOfflinePaymentWaitingPeriod(Event event, ConfigurationManager configurationManager) {
        try {
            return getOfflinePaymentWaitingPeriod(event, configurationManager) >= 0;
        } catch (OfflinePaymentException e) {
            return false;
        }
    }


    /**
     * ValidPaymentMethod should be configured in organisation and event. And if even already started then event should not have PaymentProxy.OFFLINE as only payment method
     *
     * @param paymentMethod
     * @param event
     * @param configurationManager
     * @return
     */
    public static boolean isValidPaymentMethod(PaymentManager.PaymentMethod paymentMethod, Event event, ConfigurationManager configurationManager) {
        return paymentMethod.isActive() && event.getAllowedPaymentProxies().contains(paymentMethod.getPaymentProxy()) && (!paymentMethod.getPaymentProxy().equals(PaymentProxy.OFFLINE) || hasValidOfflinePaymentWaitingPeriod(event, configurationManager));
    }

    private void reTransitionToPending(String reservationId) {
        int updatedReservation = getTicketReservationRepository().updateReservationStatus(reservationId, TicketReservationStatus.PENDING.toString());
        Validate.isTrue(updatedReservation == 1, "expected exactly one updated reservation, got "+updatedReservation);
    }
    
    //check internal consistency between the 3 values
    public Optional<Triple<Event, TicketReservation, Ticket>> from(String eventName, String reservationId, String ticketIdentifier) {
        return optionally(() -> Triple.of(getEventRepository().findByShortName(eventName), 
                getTicketReservationRepository().findReservationById(reservationId), 
                getTicketRepository().findByUUID(ticketIdentifier))).flatMap((x) -> {
                    
                    Ticket t = x.getRight();
                    Event e = x.getLeft();
                    TicketReservation tr = x.getMiddle();
                    
                    if(tr.getId().equals(t.getTicketsReservationId()) && e.getId() == t.getEventId()) {
                        return Optional.of(x);
                    } else {
                        return Optional.empty();
                    }
                    
                });
    }

    /**
     * Set the tickets attached to the reservation to the ACQUIRED state and the ticket reservation to the COMPLETE state. Additionally it will save email/fullName/billingaddress/userLanguage.
     */
    void completeReservation(int eventId, String reservationId, String email, CustomerName customerName,
                                     Locale userLanguage, String billingAddress, Optional<String> specialPriceSessionId,
                                     PaymentProxy paymentProxy) {
        if(paymentProxy != PaymentProxy.OFFLINE) {
            TicketStatus ticketStatus = paymentProxy.isDeskPaymentRequired() ? TicketStatus.TO_BE_PAID : TicketStatus.ACQUIRED;
            AdditionalServiceItemStatus asStatus = paymentProxy.isDeskPaymentRequired() ? AdditionalServiceItemStatus.TO_BE_PAID : AdditionalServiceItemStatus.ACQUIRED;
            acquireItems(ticketStatus, asStatus, paymentProxy, reservationId, email, customerName, userLanguage.getLanguage(), billingAddress, eventId);
            final TicketReservation reservation = getTicketReservationRepository().findReservationById(reservationId);
            getPluginManager().handleReservationConfirmation(reservation, eventId);
            getExtensionManager().handleReservationConfirmation(reservation, eventId);
            //cleanup unused special price codes...
            specialPriceSessionId.ifPresent(getSpecialPriceRepository()::unbindFromSession);
        }

        getAuditingRepository().insert(reservationId, null, eventId, Audit.EventType.RESERVATION_COMPLETE, new Date(), Audit.EntityType.RESERVATION, reservationId);
    }

    private void acquireItems(TicketStatus ticketStatus, AdditionalServiceItemStatus asStatus, PaymentProxy paymentProxy, String reservationId, String email, CustomerName customerName, String userLanguage, String billingAddress, int eventId) {
        Map<Integer, Ticket> preUpdateTicket = getTicketRepository().findTicketsInReservation(reservationId).stream().collect(toMap(Ticket::getId, Function.identity()));
        int updatedTickets = getTicketRepository().updateTicketsStatusWithReservationId(reservationId, ticketStatus.toString());
        Map<Integer, Ticket> postUpdateTicket = getTicketRepository().findTicketsInReservation(reservationId).stream().collect(toMap(Ticket::getId, Function.identity()));

        postUpdateTicket.forEach((id, ticket) -> {
            auditUpdateTicket(preUpdateTicket.get(id), Collections.emptyMap(), ticket, Collections.emptyMap(), eventId);
        });

        int updatedAS = getAdditionalServiceItemRepository().updateItemsStatusWithReservationUUID(reservationId, asStatus);
        Validate.isTrue(updatedTickets + updatedAS > 0, "no items have been updated");
        getSpecialPriceRepository().updateStatusForReservation(singletonList(reservationId), Status.TAKEN.toString());
        ZonedDateTime timestamp = ZonedDateTime.now(ZoneId.of("UTC"));
        int updatedReservation = getTicketReservationRepository().updateTicketReservation(reservationId, TicketReservationStatus.COMPLETE.toString(), email,
            customerName.getFullName(), customerName.getFirstName(), customerName.getLastName(), userLanguage, billingAddress, timestamp, paymentProxy.toString());
        Validate.isTrue(updatedReservation == 1, "expected exactly one updated reservation, got " + updatedReservation);
        getWaitingQueueManager().fireReservationConfirmed(reservationId);
        if(paymentProxy == PaymentProxy.PAYPAL || paymentProxy == PaymentProxy.ADMIN) {
            //we must notify the plugins about ticket assignment and send them by email
            Event event = getEventRepository().findByReservationId(reservationId);
            TicketReservation reservation = findById(reservationId).orElseThrow(IllegalStateException::new);
            findTicketsInReservation(reservationId).stream()
                .filter(ticket -> StringUtils.isNotBlank(ticket.getFullName()) || StringUtils.isNotBlank(ticket.getFirstName()) || StringUtils.isNotBlank(ticket.getEmail()))
                .forEach(ticket -> {
                    Locale locale = Locale.forLanguageTag(ticket.getUserLanguage());
                    if(paymentProxy == PaymentProxy.PAYPAL) {
                        sendTicketByEmail(ticket, locale, event, getTicketEmailGenerator(event, reservation, locale));
                    }
                    getPluginManager().handleTicketAssignment(ticket);
                    getExtensionManager().handleTicketAssignment(ticket);
                });

        }
    }

    private WaitingQueueManager getWaitingQueueManager() {
		return waitingQueueManager;
	}

	PartialTicketTextGenerator getTicketEmailGenerator(Event event, TicketReservation reservation, Locale locale) {
        return (t) -> {
            Map<String, Object> model = new HashMap<>();
            model.put("organization", getOrganizationRepository().getById(event.getOrganizationId()));
            model.put("event", event);
            model.put("ticketReservation", reservation);
            model.put("ticketUrl", ticketUpdateUrl(event, t.getUuid()));
            model.put("ticket", t);
            TicketCategory category = getTicketCategoryRepository().getById(t.getCategoryId());
            TemplateResource.fillTicketValidity(event, category, model);
            model.put("googleCalendarUrl", EventUtil.getGoogleCalendarURL(event, category, null));
            return getTemplateManager().renderTemplate(event, TemplateResource.TICKET_EMAIL, model, locale);
        };
    }

    @Transactional
    void cleanupExpiredReservations(Date expirationDate) {
        List<String> expiredReservationIds = getTicketReservationRepository().findExpiredReservation(expirationDate);
        if(expiredReservationIds.isEmpty()) {
            return;
        }
        
        getSpecialPriceRepository().resetToFreeAndCleanupForReservation(expiredReservationIds);
        getTicketRepository().resetCategoryIdForUnboundedCategories(expiredReservationIds);
        getTicketFieldRepository().deleteAllValuesForReservations(expiredReservationIds);
        getTicketRepository().freeFromReservation(expiredReservationIds);
        getWaitingQueueManager().cleanExpiredReservations(expiredReservationIds);

        //
        Map<Integer, List<ReservationIdAndEventId>> reservationIdsByEvent = getTicketReservationRepository()
            .getReservationIdAndEventId(expiredReservationIds)
            .stream()
            .collect(Collectors.groupingBy(ReservationIdAndEventId::getEventId));
        reservationIdsByEvent.forEach((eventId, reservations) -> {
            Event event = getEventRepository().findById(eventId);
            getExtensionManager().handleReservationsExpiredForEvent(event, reservations.stream().map(ReservationIdAndEventId::getId).collect(Collectors.toList()));
        });
        //
        getTicketReservationRepository().remove(expiredReservationIds);
    }

    private TicketFieldRepository getTicketFieldRepository() {
		return ticketFieldRepository;
	}

	void cleanupExpiredOfflineReservations(Date expirationDate) {
        getTicketReservationRepository().findExpiredOfflineReservations(expirationDate).forEach(this::cleanupOfflinePayment);
    }

    private void cleanupOfflinePayment(String reservationId) {
        try {
            getRequiresNewTransactionTemplate().execute((tc) -> {
                deleteOfflinePayment(getEventRepository().findByReservationId(reservationId), reservationId, true);
                return null;
            });
        } catch (Exception e) {
            log.error("error during reservation cleanup (id "+reservationId+")", e);
        }
    }

    /**
     * Finds all the reservations that are "stuck" in payment status.
     * This could happen when there is an internal error after a successful credit card charge.
     *
     * @param expirationDate expiration date
     */
    public void markExpiredInPaymentReservationAsStuck(Date expirationDate) {
        List<String> stuckReservations = getTicketReservationRepository().findStuckReservations(expirationDate);
        if(!stuckReservations.isEmpty()) {
            getTicketReservationRepository().updateReservationsStatus(stuckReservations, TicketReservationStatus.STUCK.name());

            Map<Integer, List<ReservationIdAndEventId>> reservationsGroupedByEvent = getTicketReservationRepository()
                .getReservationIdAndEventId(stuckReservations)
                .stream()
                .collect(Collectors.groupingBy(ReservationIdAndEventId::getEventId));

            reservationsGroupedByEvent.forEach((eventId, reservationIds) -> {
                Event event = getEventRepository().findById(eventId);
                Organization organization = getOrganizationRepository().getById(event.getOrganizationId());
                getNotificationManager().sendSimpleEmail(event, organization.getEmail(),
                    STUCK_TICKETS_SUBJECT,  () -> String.format(STUCK_TICKETS_MSG, event.getShortName()));

                getExtensionManager().handleStuckReservations(event, reservationIds.stream().map(ReservationIdAndEventId::getId).collect(toList()));
            });
        }
    }

    private static TotalPrice totalReservationCostWithVAT(PromoCodeDiscount promoCodeDiscount,
                                                          Event event,
                                                          PriceContainer.VatStatus reservationVatStatus,
                                                          List<Ticket> tickets,
                                                          Stream<Pair<AdditionalService, List<AdditionalServiceItem>>> additionalServiceItems) {

        List<TicketPriceContainer> ticketPrices = tickets.stream().map(t -> TicketPriceContainer.from(t, reservationVatStatus, event, promoCodeDiscount)).collect(toList());
        BigDecimal totalVAT = ticketPrices.stream().map(TicketPriceContainer::getVAT).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDiscount = ticketPrices.stream().map(TicketPriceContainer::getAppliedDiscount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalNET = ticketPrices.stream().map(TicketPriceContainer::getFinalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        int discountedTickets = (int) ticketPrices.stream().filter(t -> t.getAppliedDiscount().compareTo(BigDecimal.ZERO) > 0).count();
        int discountAppliedCount = discountedTickets <= 1 || promoCodeDiscount.getDiscountType() == DiscountType.FIXED_AMOUNT ? discountedTickets : 1;

        List<AdditionalServiceItemPriceContainer> asPrices = additionalServiceItems
            .flatMap(generateASIPriceContainers(event, null))
            .collect(toList());

        BigDecimal asTotalVAT = asPrices.stream().map(AdditionalServiceItemPriceContainer::getVAT).reduce(BigDecimal.ZERO, BigDecimal::add);
        //FIXME discount is not applied to donations, as it wouldn't make sense. Must be implemented for #111
        BigDecimal asTotalNET = asPrices.stream().map(AdditionalServiceItemPriceContainer::getFinalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TotalPrice(unitToCents(totalNET.add(asTotalNET)), unitToCents(totalVAT.add(asTotalVAT)), -(MonetaryUtil.unitToCents(totalDiscount)), discountAppliedCount);
    }

    private static Function<Pair<AdditionalService, List<AdditionalServiceItem>>, Stream<? extends AdditionalServiceItemPriceContainer>> generateASIPriceContainers(Event event, PromoCodeDiscount discount) {
        return p -> p.getValue().stream().map(asi -> AdditionalServiceItemPriceContainer.from(asi, p.getKey(), event, discount));
    }

    /**
     * Get the total cost with VAT if it's not included in the ticket price.
     * 
     * @param reservationId
     * @return
     */
    public TotalPrice totalReservationCostWithVAT(String reservationId) {
        TicketReservation reservation = getTicketReservationRepository().findReservationById(reservationId);
        
        Optional<PromoCodeDiscount> promoCodeDiscount = Optional.ofNullable(reservation.getPromoCodeDiscountId()).map(getPromoCodeDiscountRepository()::findById);
        
        Event event = getEventRepository().findByReservationId(reservationId);
        List<Ticket> tickets = getTicketRepository().findTicketsInReservation(reservationId);

        return totalReservationCostWithVAT(promoCodeDiscount.orElse(null), event, reservation.getVatStatus(), tickets, collectAdditionalServiceItems(reservationId, event));
    }

    private String formatPromoCode(PromoCodeDiscount promoCodeDiscount, List<Ticket> tickets) {

        List<Ticket> filteredTickets = tickets.stream().filter(ticket -> promoCodeDiscount.getCategories().contains(ticket.getCategoryId())).collect(toList());

        if (promoCodeDiscount.getCategories().isEmpty() || filteredTickets.isEmpty()) {
            return promoCodeDiscount.getPromoCode();
        }

        String formattedDiscountedCategories = filteredTickets.stream()
            .map(Ticket::getCategoryId)
            .collect(toSet())
            .stream()
            .map(categoryId -> getTicketCategoryRepository().getByIdAndActive(categoryId, promoCodeDiscount.getEventId()).getName())
            .collect(Collectors.joining(", ", "(", ")"));


        return promoCodeDiscount.getPromoCode() + " " + formattedDiscountedCategories;
    }

    public OrderSummary orderSummaryForReservationId(String reservationId, Event event, Locale locale) {
        TicketReservation reservation = getTicketReservationRepository().findReservationById(reservationId);
        TotalPrice reservationCost = totalReservationCostWithVAT(reservationId);
        PromoCodeDiscount discount = Optional.ofNullable(reservation.getPromoCodeDiscountId()).map(getPromoCodeDiscountRepository()::findById).orElse(null);
        //
        boolean free = reservationCost.getPriceWithVAT() == 0;
        String vat = getVAT(event).orElse(null);
        
        return new OrderSummary(reservationCost,
                extractSummary(reservationId, reservation.getVatStatus(), event, locale, discount, reservationCost), free,
                formatCents(reservationCost.getPriceWithVAT()), formatCents(reservationCost.getVAT()),
                reservation.getStatus() == TicketReservationStatus.OFFLINE_PAYMENT,
                reservation.getPaymentMethod() == PaymentProxy.ON_SITE, vat, reservation.getVatStatus());
    }
    
    List<SummaryRow> extractSummary(String reservationId, PriceContainer.VatStatus reservationVatStatus,
                                    Event event, Locale locale, PromoCodeDiscount promoCodeDiscount, TotalPrice reservationCost) {
        List<SummaryRow> summary = new ArrayList<>();
        List<TicketPriceContainer> tickets = getTicketRepository().findTicketsInReservation(reservationId).stream()
            .map(t -> TicketPriceContainer.from(t, reservationVatStatus, event, promoCodeDiscount)).collect(toList());
        tickets.stream()
            .collect(Collectors.groupingBy(TicketPriceContainer::getCategoryId))
            .forEach((categoryId, ticketsByCategory) -> {
                final int subTotal = ticketsByCategory.stream().mapToInt(TicketPriceContainer::getSummarySrcPriceCts).sum();
                final int subTotalBeforeVat = ticketsByCategory.stream().mapToInt(TicketPriceContainer::getSummaryPriceBeforeVatCts).sum();
                TicketPriceContainer firstTicket = ticketsByCategory.get(0);
                final int ticketPriceCts = firstTicket.getSummarySrcPriceCts();
                final int priceBeforeVat = firstTicket.getSummaryPriceBeforeVatCts();
                String categoryName = getTicketCategoryRepository().getByIdAndActive(categoryId, event.getId()).getName();
                summary.add(new SummaryRow(categoryName, formatCents(ticketPriceCts), formatCents(priceBeforeVat), ticketsByCategory.size(), formatCents(subTotal), formatCents(subTotalBeforeVat), subTotal, SummaryRow.SummaryType.TICKET));
            });

        summary.addAll(collectAdditionalServiceItems(reservationId, event)
            .map(entry -> {
                String language = locale.getLanguage();
                AdditionalServiceText title = getAdditionalServiceTextRepository().findBestMatchByLocaleAndType(entry.getKey().getId(), language, AdditionalServiceText.TextType.TITLE);
                if(!title.getLocale().equals(language) || title.getId() == -1) {
                    log.debug("additional service {}: title not found for locale {}", title.getAdditionalServiceId(), language);
                }
                List<AdditionalServiceItemPriceContainer> prices = generateASIPriceContainers(event, null).apply(entry).collect(toList());
                AdditionalServiceItemPriceContainer first = prices.get(0);
                final int subtotal = prices.stream().mapToInt(AdditionalServiceItemPriceContainer::getSrcPriceCts).sum();
                final int subtotalBeforeVat = prices.stream().mapToInt(AdditionalServiceItemPriceContainer::getSummaryPriceBeforeVatCts).sum();
                return new SummaryRow(title.getValue(), formatCents(first.getSrcPriceCts()), formatCents(first.getSummaryPriceBeforeVatCts()), prices.size(), formatCents(subtotal), formatCents(subtotalBeforeVat), subtotal, SummaryRow.SummaryType.ADDITIONAL_SERVICE);
            }).collect(Collectors.toList()));

        Optional.ofNullable(promoCodeDiscount).ifPresent(promo -> {
            String formattedSingleAmount = "-" + (promo.getDiscountType() == DiscountType.FIXED_AMOUNT ? formatCents(promo.getDiscountAmount()) : (promo.getDiscountAmount()+"%"));
            summary.add(new SummaryRow(formatPromoCode(promo, getTicketRepository().findTicketsInReservation(reservationId)),
                formattedSingleAmount,
                formattedSingleAmount,
                reservationCost.getDiscountAppliedCount(),
                formatCents(reservationCost.getDiscount()), formatCents(reservationCost.getDiscount()), reservationCost.getDiscount(), SummaryRow.SummaryType.PROMOTION_CODE));
        });
        return summary;
    }

    private AdditionalServiceTextRepository getAdditionalServiceTextRepository() {
		return additionalServiceTextRepository;
	}

	private Stream<Pair<AdditionalService, List<AdditionalServiceItem>>> collectAdditionalServiceItems(String reservationId, Event event) {
        return getAdditionalServiceItemRepository().findByReservationUuid(reservationId)
            .stream()
            .collect(Collectors.groupingBy(AdditionalServiceItem::getAdditionalServiceId))
            .entrySet()
            .stream()
            .map(entry -> Pair.of(getAdditionalServiceRepository().getById(entry.getKey(), event.getId()), entry.getValue()));
    }

    String reservationUrl(String reservationId) {
        return reservationUrl(reservationId, getEventRepository().findByReservationId(reservationId));
    }

    public String reservationUrl(String reservationId, Event event) {
        TicketReservation reservation = getTicketReservationRepository().findReservationById(reservationId);
        return StringUtils.removeEnd(getConfigurationManager().getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BASE_URL)), "/")
                + "/event/" + event.getShortName() + "/reservation/" + reservationId + "?lang="+reservation.getUserLanguage();
    }

    String ticketUrl(Event event, String ticketId) {
        Ticket ticket = getTicketRepository().findByUUID(ticketId);
        return StringUtils.removeEnd(getConfigurationManager().getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BASE_URL)), "/")
                + "/event/" + event.getShortName() + "/ticket/" + ticketId + "?lang=" + ticket.getUserLanguage();
    }

    public String ticketUpdateUrl(Event event, String ticketId) {
        Ticket ticket = getTicketRepository().findByUUID(ticketId);
        return StringUtils.removeEnd(getConfigurationManager().getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BASE_URL)), "/")
            + "/event/" + event.getShortName() + "/ticket/" + ticketId + "/update?lang="+ticket.getUserLanguage();
    }

    public int maxAmountOfTicketsForCategory(int organizationId, int eventId, int ticketCategoryId) {
        return getConfigurationManager().getIntConfigValue(Configuration.from(organizationId, eventId, ticketCategoryId, ConfigurationKeys.MAX_AMOUNT_OF_TICKETS_BY_RESERVATION), 5);
    }
    
    public Optional<TicketReservation> findById(String reservationId) {
        return getTicketReservationRepository().findOptionalReservationById(reservationId);
    }

    private Optional<TicketReservation> findByIdForNotification(String reservationId, ZoneId eventZoneId, int quietPeriod) {
        return findById(reservationId).filter(notificationNotSent(eventZoneId, quietPeriod));
    }

    private static Predicate<TicketReservation> notificationNotSent(ZoneId eventZoneId, int quietPeriod) {
        return r -> r.latestNotificationTimestamp(eventZoneId)
                .map(t -> t.truncatedTo(ChronoUnit.DAYS).plusDays(quietPeriod).isBefore(ZonedDateTime.now(eventZoneId).truncatedTo(ChronoUnit.DAYS)))
                .orElse(true);
    }

    public void cancelPendingReservation(String reservationId, boolean expired) {
        Validate.isTrue(getTicketReservationRepository().findReservationById(reservationId).getStatus() == TicketReservationStatus.PENDING, "status is not PENDING");
        cancelReservation(reservationId, expired);
    }

    private void cancelReservation(String reservationId, boolean expired) {
        List<String> reservationIdsToRemove = singletonList(reservationId);
        getSpecialPriceRepository().resetToFreeAndCleanupForReservation(reservationIdsToRemove);
        getTicketRepository().resetCategoryIdForUnboundedCategories(reservationIdsToRemove);
        getTicketFieldRepository().deleteAllValuesForReservations(reservationIdsToRemove);
        Event event = getEventRepository().findByReservationId(reservationId);
        int updatedAS = getAdditionalServiceItemRepository().updateItemsStatusWithReservationUUID(reservationId, expired ? AdditionalServiceItemStatus.EXPIRED : AdditionalServiceItemStatus.CANCELLED);
        int updatedTickets = getTicketRepository().findTicketsInReservation(reservationId).stream().mapToInt(t -> ticketRepository.releaseExpiredTicket(reservationId, event.getId(), t.getId())).sum();
        Validate.isTrue(updatedTickets  + updatedAS > 0, "no items have been updated");
        getWaitingQueueManager().fireReservationExpired(reservationId);
        deleteReservation(event, reservationId, expired);
        getAuditingRepository().insert(reservationId, null, event.getId(), expired ? Audit.EventType.CANCEL_RESERVATION_EXPIRED : Audit.EventType.CANCEL_RESERVATION, new Date(), Audit.EntityType.RESERVATION, reservationId);
    }

    private void deleteReservation(Event event, String reservationIdToRemove, boolean expired) {
        //handle removal of ticket
        List<String> wrappedReservationIdToRemove = Collections.singletonList(reservationIdToRemove);
        getWaitingQueueManager().cleanExpiredReservations(wrappedReservationIdToRemove);
        //
        if(expired) {
            getExtensionManager().handleReservationsExpiredForEvent(event, wrappedReservationIdToRemove);
        } else {
            getExtensionManager().handleReservationsCancelledForEvent(event, wrappedReservationIdToRemove);
        }
        //
        int removedReservation = getTicketReservationRepository().remove(wrappedReservationIdToRemove);
        Validate.isTrue(removedReservation == 1, "expected exactly one removed reservation, got " + removedReservation);
    }

    public Optional<SpecialPrice> getSpecialPriceByCode(String code) {
        return getSpecialPriceRepository().getByCode(code);
    }

    public Optional<SpecialPrice> renewSpecialPrice(Optional<SpecialPrice> specialPrice, Optional<String> specialPriceSessionId) {
        Validate.isTrue(specialPrice.isPresent(), "special price is not present");

        SpecialPrice price = specialPrice.get();

        if(!specialPriceSessionId.isPresent()) {
            log.warn("cannot renew special price {}: session identifier not found or not matching", price.getCode());
            return Optional.empty();
        }

        if(price.getStatus() == Status.PENDING && !StringUtils.equals(price.getSessionIdentifier(), specialPriceSessionId.get())) {
            log.warn("cannot renew special price {}: session identifier not found or not matching", price.getCode());
            return Optional.empty();
        }

        if(price.getStatus() == Status.FREE) {
            getSpecialPriceRepository().bindToSession(price.getId(), specialPriceSessionId.get());
            return getSpecialPriceByCode(price.getCode());
        } else if(price.getStatus() == Status.PENDING) {
            Optional<Ticket> optionalTicket = ticketRepository.findBySpecialPriceId(price.getId());
            if(optionalTicket.isPresent()) {
                cancelPendingReservation(optionalTicket.get().getTicketsReservationId(), false);
                return getSpecialPriceByCode(price.getCode());
            }
        }

        return specialPrice;
    }

    public List<Ticket> findTicketsInReservation(String reservationId) {
        return getTicketRepository().findTicketsInReservation(reservationId);
    }

    public List<Triple<AdditionalService, List<AdditionalServiceText>, AdditionalServiceItem>> findAdditionalServicesInReservation(String reservationId) {
        return getAdditionalServiceItemRepository().findByReservationUuid(reservationId).stream()
            .map(asi -> Triple.of(getAdditionalServiceRepository().getById(asi.getAdditionalServiceId(), asi.getEventId()), getAdditionalServiceTextRepository().findAllByAdditionalServiceId(asi.getAdditionalServiceId()), asi))
            .collect(Collectors.toList());
    }

    public Optional<String> getVAT(Event event) {
        return getConfigurationManager().getStringConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.VAT_NR));
    }

    public void updateTicketOwner(Ticket ticket,
                                  Locale locale,
                                  Event event,
                                  UpdateTicketOwnerForm updateTicketOwner,
                                  PartialTicketTextGenerator confirmationTextBuilder,
                                  PartialTicketTextGenerator ownerChangeTextBuilder,
                                  Optional<UserDetails> userDetails) {

        Ticket preUpdateTicket = getTicketRepository().findByUUID(ticket.getUuid());
        Map<String, String> preUpdateTicketFields = getTicketFieldRepository().findAllByTicketId(ticket.getId()).stream().collect(Collectors.toMap(TicketFieldValue::getName, TicketFieldValue::getValue));

        String newEmail = updateTicketOwner.getEmail().trim();
        CustomerName customerName = new CustomerName(updateTicketOwner.getFullName(), updateTicketOwner.getFirstName(), updateTicketOwner.getLastName(), event);
        getTicketRepository().updateTicketOwner(ticket.getUuid(), newEmail, customerName.getFullName(), customerName.getFirstName(), customerName.getLastName());

        //
        Locale userLocale = Optional.ofNullable(StringUtils.trimToNull(updateTicketOwner.getUserLanguage())).map(Locale::forLanguageTag).orElse(locale);

        getTicketRepository().updateOptionalTicketInfo(ticket.getUuid(), userLocale.getLanguage());
        getTicketFieldRepository().updateOrInsert(updateTicketOwner.getAdditional(), ticket.getId(), event.getId());

        Ticket newTicket = getTicketRepository().findByUUID(ticket.getUuid());
        if (newTicket.getStatus() == TicketStatus.ACQUIRED
            && (!StringUtils.equalsIgnoreCase(newEmail, ticket.getEmail()) || !StringUtils.equalsIgnoreCase(customerName.getFullName(), ticket.getFullName()))) {
            sendTicketByEmail(newTicket, userLocale, event, confirmationTextBuilder);
        }

        boolean admin = isAdmin(userDetails);

        notAdminUpdateTicketOwner(ticket, event, ownerChangeTextBuilder, newEmail, newTicket, admin);

        adminUpdateTicketOwner(ticket, userDetails, admin);
        getPluginManager().handleTicketAssignment(newTicket);
        getExtensionManager().handleTicketAssignment(newTicket);



        Ticket postUpdateTicket = getTicketRepository().findByUUID(ticket.getUuid());
        Map<String, String> postUpdateTicketFields = getTicketFieldRepository().findAllByTicketId(ticket.getId()).stream().collect(Collectors.toMap(TicketFieldValue::getName, TicketFieldValue::getValue));

        auditUpdateTicket(preUpdateTicket, preUpdateTicketFields, postUpdateTicket, postUpdateTicketFields, event.getId());
    }

	private void adminUpdateTicketOwner(Ticket ticket, Optional<UserDetails> userDetails, boolean admin) {
		if(admin) {
            TicketReservation reservation = findById(ticket.getTicketsReservationId()).orElseThrow(IllegalStateException::new);
            //if the current user is admin, then it would be good to update also the name of the Reservation Owner
            String username = userDetails.get().getUsername();
            log.warn("Reservation {}: forced assignee replacement old: {} new: {}", reservation.getId(), reservation.getFullName(), username);
            getTicketReservationRepository().updateAssignee(reservation.getId(), username);
        }
	}

	private void notAdminUpdateTicketOwner(Ticket ticket, Event event, PartialTicketTextGenerator ownerChangeTextBuilder,
			String newEmail, Ticket newTicket, boolean admin) {
		if (!admin && StringUtils.isNotBlank(ticket.getEmail()) && !StringUtils.equalsIgnoreCase(newEmail, ticket.getEmail()) && ticket.getStatus() == TicketStatus.ACQUIRED) {
            Locale oldUserLocale = Locale.forLanguageTag(ticket.getUserLanguage());
            String subject = getMessageSource().getMessage("ticket-has-changed-owner-subject", new Object[] {event.getDisplayName()}, oldUserLocale);
            getNotificationManager().sendSimpleEmail(event, ticket.getEmail(), subject, () -> ownerChangeTextBuilder.generate(newTicket));
            if(event.getBegin().isBefore(ZonedDateTime.now(event.getZoneId()))) {
                Organization organization = getOrganizationRepository().getById(event.getOrganizationId());
                getNotificationManager().sendSimpleEmail(event, organization.getEmail(), "WARNING: Ticket has been reassigned after event start", () -> ownerChangeTextBuilder.generate(newTicket));
            }
        }
	}

    private void auditUpdateTicket(Ticket preUpdateTicket, Map<String, String> preUpdateTicketFields, Ticket postUpdateTicket, Map<String, String> postUpdateTicketFields, int eventId) {
        DiffNode diffTicket = ObjectDifferBuilder.buildDefault().compare(postUpdateTicket, preUpdateTicket);
        DiffNode diffTicketFields = ObjectDifferBuilder.buildDefault().compare(postUpdateTicketFields, preUpdateTicketFields);
        FieldChangesSaver diffTicketVisitor = new FieldChangesSaver(preUpdateTicket, postUpdateTicket);
        FieldChangesSaver diffTicketFieldsVisitor = new FieldChangesSaver(preUpdateTicketFields, postUpdateTicketFields);
        diffTicket.visit(diffTicketVisitor);
        diffTicketFields.visit(diffTicketFieldsVisitor);

        List<Map<String, Object>> changes = new ArrayList<>(diffTicketVisitor.changes);
        changes.addAll(diffTicketFieldsVisitor.changes);

        getAuditingRepository().insert(preUpdateTicket.getTicketsReservationId(), null, eventId,
            Audit.EventType.UPDATE_TICKET, new Date(), Audit.EntityType.TICKET, Integer.toString(preUpdateTicket.getId()), changes);
    }


    private static class FieldChangesSaver implements DiffNode.Visitor {

        private final Object preBase;
        private final Object postBase;

        private final List<Map<String, Object>> changes = new ArrayList<>();


        FieldChangesSaver(Object preBase, Object postBase) {
            this.preBase = preBase;
            this.postBase = postBase;
        }

        @Override
        public void node(DiffNode node, Visit visit) {
            if(node.hasChanges() && node.getState() != DiffNode.State.UNTOUCHED && !node.isRootNode()) {
                Object baseValue = node.canonicalGet(preBase);
                Object workingValue = node.canonicalGet(postBase);
                HashMap<String, Object> change = new HashMap<>();
                change.put("propertyName", node.getPath().toString());
                change.put("state", node.getState());
                change.put("oldValue", baseValue);
                change.put("newValue", workingValue);
                changes.add(change);
            }
        }
    }

    private boolean isAdmin(Optional<UserDetails> userDetails) {
        return userDetails.flatMap(u -> u.getAuthorities().stream().map(a -> Role.fromRoleName(a.getAuthority())).filter(Role.ADMIN::equals).findFirst()).isPresent();
    }

    void sendTicketByEmail(Ticket ticket, Locale locale, Event event, PartialTicketTextGenerator confirmationTextBuilder) {
        try {
            TicketReservation reservation = getTicketReservationRepository().findReservationById(ticket.getTicketsReservationId());
            TicketCategory ticketCategory = getTicketCategoryRepository().getByIdAndActive(ticket.getCategoryId(), event.getId());
            getNotificationManager().sendTicketByEmail(ticket, event, locale, confirmationTextBuilder, reservation, ticketCategory);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Triple<Event, TicketReservation, Ticket>> fetchComplete(String eventName, String ticketIdentifier) {
        return getTicketRepository().findOptionalByUUID(ticketIdentifier)
            .flatMap(ticket -> from(eventName, ticket.getTicketsReservationId(), ticketIdentifier)
                .flatMap((triple) -> {
                    if(triple.getMiddle().getStatus() == TicketReservationStatus.COMPLETE) {
                        return Optional.of(triple);
                    } else {
                        return Optional.empty();
                    }
            }));
    }

    /**
     * Return a fully present triple only if the values are present (obviously) and the the reservation has a COMPLETE status and the ticket is considered assigned.
     *
     * @param eventName
     * @param ticketIdentifier
     * @return
     */
    public Optional<Triple<Event, TicketReservation, Ticket>> fetchCompleteAndAssigned(String eventName, String ticketIdentifier) {
        return fetchComplete(eventName, ticketIdentifier).flatMap((t) -> {
            if (t.getRight().getAssigned()) {
                return Optional.of(t);
            } else {
                return Optional.empty();
            }
        });
    }

    void sendReminderForOfflinePayments() {
        Date expiration = truncate(addHours(new Date(), getConfigurationManager().getIntConfigValue(Configuration.getSystemConfiguration(OFFLINE_REMINDER_HOURS), 24)), Calendar.DATE);
        getTicketReservationRepository().findAllOfflinePaymentReservationForNotification(expiration).stream()
                .map(reservation -> {
                    Optional<Ticket> ticket = getTicketRepository().findFirstTicketInReservation(reservation.getId());
                    Optional<Event> event = ticket.map(t -> getEventRepository().findById(t.getEventId()));
                    Optional<Locale> locale = ticket.map(t -> Locale.forLanguageTag(t.getUserLanguage()));
                    return Triple.of(reservation, event, locale);
                })
                .filter(p -> p.getMiddle().isPresent())
                .filter(p -> {
                    Event event = p.getMiddle().get();
                    return truncate(addHours(new Date(), getConfigurationManager().getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), OFFLINE_REMINDER_HOURS), 24)), Calendar.DATE).compareTo(p.getLeft().getValidity()) >= 0;
                })
                .map(p -> Triple.of(p.getLeft(), p.getMiddle().get(), p.getRight().get()))
                .forEach(p -> {
                    TicketReservation reservation = p.getLeft();
                    Event event = p.getMiddle();
                    Map<String, Object> model = prepareModelForReservationEmail(event, reservation);
                    Locale locale = p.getRight();
                    getTicketReservationRepository().flagAsOfflinePaymentReminderSent(reservation.getId());
                    getNotificationManager().sendSimpleEmail(event, reservation.getEmail(), getMessageSource().getMessage("reservation.reminder.mail.subject", new Object[]{getShortReservationID(event, reservation.getId())}, locale), () -> getTemplateManager().renderTemplate(event, TemplateResource.REMINDER_EMAIL, model, locale));
                });
    }

    //called each hour
    void sendReminderForOfflinePaymentsToEventManagers() {
        getEventRepository().findAllActives(ZonedDateTime.now(Clock.systemUTC())).stream().filter(event -> {
            ZonedDateTime dateTimeForEvent = ZonedDateTime.now(event.getZoneId());
            return dateTimeForEvent.truncatedTo(ChronoUnit.HOURS).getHour() == 5; //only for the events at 5:00 local time
        }).forEachOrdered(event -> {
            ZonedDateTime dateTimeForEvent = ZonedDateTime.now(event.getZoneId()).truncatedTo(ChronoUnit.DAYS).plusDays(1);
            List<TicketReservationInfo> reservations = getTicketReservationRepository().findAllOfflinePaymentReservationWithExpirationBefore(dateTimeForEvent, event.getId());
            log.info("for event {} there are {} pending offline payments to handle", event.getId(), reservations.size());
            if(!reservations.isEmpty()) {
                Organization organization = getOrganizationRepository().getById(event.getOrganizationId());
                List<String> cc = getNotificationManager().getCCForEventOrganizer(event);
                String subject = String.format("There are %d pending offline payments that will expire in event: %s", reservations.size(), event.getDisplayName());
                String baseUrl = getConfigurationManager().getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), BASE_URL));
                Map<String, Object> model = TemplateResource.prepareModelForOfflineReservationExpiringEmailForOrganizer(event, reservations, baseUrl);
                getNotificationManager().sendSimpleEmail(event, organization.getEmail(), cc, subject, () ->
                    getTemplateManager().renderTemplate(event, TemplateResource.OFFLINE_RESERVATION_EXPIRING_EMAIL_FOR_ORGANIZER, model, Locale.ENGLISH));
                getExtensionManager().handleOfflineReservationsWillExpire(event, reservations);
            }
        });
    }

    void sendReminderForTicketAssignment() {
        getNotifiableEventsStream()
                .map(e -> Pair.of(e, getTicketRepository().findAllReservationsConfirmedButNotAssigned(e.getId())))
                .filter(p -> !p.getRight().isEmpty())
                .forEach(p -> Wrappers.voidTransactionWrapper(this::sendAssignmentReminder, p));
    }

    void sendReminderForOptionalData() {
        getNotifiableEventsStream()
                .filter(e -> getConfigurationManager().getBooleanConfigValue(Configuration.from(e.getOrganizationId(), e.getId(), OPTIONAL_DATA_REMINDER_ENABLED), true))
                .filter(e -> getTicketFieldRepository().countAdditionalFieldsForEvent(e.getId()) > 0)
                .map(e -> Pair.of(e, getTicketRepository().findAllAssignedButNotYetNotified(e.getId())))
                .filter(p -> !p.getRight().isEmpty())
                .forEach(p -> Wrappers.voidTransactionWrapper(this::sendOptionalDataReminder, p));
    }

    private void sendOptionalDataReminder(Pair<Event, List<Ticket>> eventAndTickets) {
        getRequiresNewTransactionTemplate().execute(ts -> {
            Event event = eventAndTickets.getLeft();
            int daysBeforeStart = getConfigurationManager().getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.ASSIGNMENT_REMINDER_START), 10);
            List<Ticket> tickets = eventAndTickets.getRight().stream().filter(t -> !getTicketFieldRepository().hasOptionalData(t.getId())).collect(toList());
            Set<String> notYetNotifiedReservations = tickets.stream().map(Ticket::getTicketsReservationId).distinct().filter(rid -> findByIdForNotification(rid, event.getZoneId(), daysBeforeStart).isPresent()).collect(toSet());
            tickets.stream()
                    .filter(t -> notYetNotifiedReservations.contains(t.getTicketsReservationId()))
                    .forEach(t -> {
                        int result = getTicketRepository().flagTicketAsReminderSent(t.getId());
                        Validate.isTrue(result == 1);
                        Map<String, Object> model = TemplateResource.prepareModelForReminderTicketAdditionalInfo(getOrganizationRepository().getById(event.getOrganizationId()), event, t, ticketUpdateUrl(event, t.getUuid()));
                        Locale locale = Optional.ofNullable(t.getUserLanguage()).map(Locale::forLanguageTag).orElseGet(() -> findReservationLanguage(t.getTicketsReservationId()));
                        getNotificationManager().sendSimpleEmail(event, t.getEmail(), getMessageSource().getMessage("reminder.ticket-additional-info.subject", new Object[]{event.getDisplayName()}, locale), () -> getTemplateManager().renderTemplate(event, TemplateResource.REMINDER_TICKET_ADDITIONAL_INFO, model, locale));
                    });
            return null;
        });
    }

    Stream<Event> getNotifiableEventsStream() {
        return getEventRepository().findAll().stream()
                .filter(e -> {
                    int daysBeforeStart = configurationManager.getIntConfigValue(Configuration.from(e.getOrganizationId(), e.getId(), ConfigurationKeys.ASSIGNMENT_REMINDER_START), 10);
                    //we don't want to define events SO far away, don't we?
                    int days = (int) ChronoUnit.DAYS.between(ZonedDateTime.now(e.getZoneId()).toLocalDate(), e.getBegin().toLocalDate());
                    return days > 0 && days <= daysBeforeStart;
                });
    }

    private void sendAssignmentReminder(Pair<Event, List<String>> p) {
        try {
            getRequiresNewTransactionTemplate().execute(status -> {
                Event event = p.getLeft();
                ZoneId eventZoneId = event.getZoneId();
                int quietPeriod = getConfigurationManager().getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.ASSIGNMENT_REMINDER_INTERVAL), 3);
                p.getRight().stream()
                        .map(id -> findByIdForNotification(id, eventZoneId, quietPeriod))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .forEach(reservation -> {
                            Map<String, Object> model = prepareModelForReservationEmail(event, reservation);
                            getTicketReservationRepository().updateLatestReminderTimestamp(reservation.getId(), ZonedDateTime.now(eventZoneId));
                            Locale locale = findReservationLanguage(reservation.getId());
                            getNotificationManager().sendSimpleEmail(event, reservation.getEmail(), getMessageSource().getMessage("reminder.ticket-not-assigned.subject", new Object[]{event.getDisplayName()}, locale), () -> getTemplateManager().renderTemplate(event, TemplateResource.REMINDER_TICKETS_ASSIGNMENT_EMAIL, model, locale));
                        });
                return null;
            });
        } catch (Exception ex) {
            log.warn("cannot send reminder message", ex);
        }
    }

    public TicketReservation findByPartialID(String reservationId) {
        Validate.notBlank(reservationId, "invalid reservationId");
        Validate.matchesPattern(reservationId, "^[^%]*$", "invalid character found");
        List<TicketReservation> results = ticketReservationRepository.findByPartialID(StringUtils.trimToEmpty(reservationId).toLowerCase() + "%");
        Validate.isTrue(results.size() > 0, "reservation not found");
        Validate.isTrue(results.size() == 1, "multiple results found. Try handling this reservation manually.");
        return results.get(0);
    }

    public String getShortReservationID(Event event, String reservationId) {
        return getConfigurationManager().getShortReservationID(event, reservationId);
    }

    public int countAvailableTickets(Event event, TicketCategory category) {
        if(category.isBounded()) {
            return getTicketRepository().countFreeTickets(event.getId(), category.getId());
        }
        return getTicketRepository().countFreeTicketsForUnbounded(event.getId());
    }

    public void releaseTicket(Event event, TicketReservation ticketReservation, final Ticket ticket) {
        TicketCategory category = getTicketCategoryRepository().getByIdAndActive(ticket.getCategoryId(), event.getId());
        if(!CategoryEvaluator.isTicketCancellationAvailable(getTicketCategoryRepository(), ticket)) {
            throw new IllegalStateException("Cannot release reserved tickets");
        }
        String reservationId = ticketReservation.getId();
        //#365 - reset UUID when releasing a ticket
        int result = getTicketRepository().releaseTicket(reservationId, UUID.randomUUID().toString(), event.getId(), ticket.getId());
        Validate.isTrue(result == 1, String.format("Expected 1 row to be updated, got %d", result));
        if(category.isAccessRestricted() || !category.isBounded()) {
            getTicketRepository().unbindTicketsFromCategory(event.getId(), category.getId(), singletonList(ticket.getId()));
        }
        Organization organization = getOrganizationRepository().getById(event.getOrganizationId());
        Map<String, Object> model = TemplateResource.buildModelForTicketHasBeenCancelled(organization, event, ticket);
        Locale locale = Locale.forLanguageTag(Optional.ofNullable(ticket.getUserLanguage()).orElse("en"));
        getNotificationManager().sendSimpleEmail(event, ticket.getEmail(), getMessageSource().getMessage("email-ticket-released.subject",
                new Object[]{event.getDisplayName()}, locale),
                () -> getTemplateManager().renderTemplate(event, TemplateResource.TICKET_HAS_BEEN_CANCELLED, model, locale));

        String ticketCategoryDescription = getTicketCategoryDescriptionRepository().findByTicketCategoryIdAndLocale(category.getId(), ticket.getUserLanguage()).orElse("");

        List<AdditionalServiceItem> additionalServiceItems = getAdditionalServiceItemRepository().findByReservationUuid(reservationId);
        Map<String, Object> adminModel = TemplateResource.buildModelForTicketHasBeenCancelledAdmin(organization, event, ticket,
            ticketCategoryDescription, additionalServiceItems, asi -> getAdditionalServiceTextRepository().findByLocaleAndType(asi.getAdditionalServiceId(), locale.getLanguage(), AdditionalServiceText.TextType.TITLE));
        getNotificationManager().sendSimpleEmail(event, organization.getEmail(), getMessageSource().getMessage("email-ticket-released.admin.subject", new Object[]{ticket.getId(), event.getDisplayName()}, locale),
            () -> getTemplateManager().renderTemplate(event, TemplateResource.TICKET_HAS_BEEN_CANCELLED_ADMIN, adminModel, locale));

        int deletedValues = getTicketFieldRepository().deleteAllValuesForTicket(ticket.getId());
        log.debug("deleting {} field values for ticket {}", deletedValues, ticket.getId());

        getAuditingRepository().insert(reservationId, null, event.getId(), Audit.EventType.CANCEL_TICKET, new Date(), Audit.EntityType.TICKET, Integer.toString(ticket.getId()));

        if(getTicketRepository().countTicketsInReservation(reservationId) == 0 && !getTransactionRepository().loadOptionalByReservationId(reservationId).isPresent()) {
            deleteReservation(event, reservationId, false);
            getAuditingRepository().insert(reservationId, null, event.getId(), Audit.EventType.CANCEL_RESERVATION, new Date(), Audit.EntityType.RESERVATION, reservationId);
        } else {
            getExtensionManager().handleTicketCancelledForEvent(event, Collections.singletonList(ticket.getUuid()));
        }
    }

    private TicketCategoryDescriptionRepository getTicketCategoryDescriptionRepository() {
		return ticketCategoryDescriptionRepository;
	}

	public int getReservationTimeout(Event event) {
        return getConfigurationManager().getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), RESERVATION_TIMEOUT), 25);
    }

    public void validateAndConfirmOfflinePayment(String reservationId, Event event, BigDecimal paidAmount, String username) {
        TicketReservation reservation = findByPartialID(reservationId);
        Optional<OrderSummary> optionalOrderSummary = optionally(() -> orderSummaryForReservationId(reservation.getId(), event, Locale.forLanguageTag(reservation.getUserLanguage())));
        Validate.isTrue(optionalOrderSummary.isPresent(), "Reservation not found");
        OrderSummary orderSummary = optionalOrderSummary.get();
        Validate.isTrue(MonetaryUtil.centsToUnit(orderSummary.getOriginalTotalPrice().getPriceWithVAT()).compareTo(paidAmount) == 0, "paid price differs from due price");
        confirmOfflinePayment(event, reservation.getId(), username);
    }

    private List<Pair<TicketReservation, OrderSummary>> fetchWaitingForPayment(int eventId, Event event, Locale locale) {
        return getTicketReservationRepository().findAllReservationsWaitingForPaymentInEventId(eventId).stream()
            .map(id -> Pair.of(getTicketReservationRepository().findReservationById(id), orderSummaryForReservationId(id, event, locale)))
            .collect(Collectors.toList());
    }

    public List<Pair<TicketReservation, OrderSummary>> getPendingPayments(Event event) {
        return fetchWaitingForPayment(event.getId(), event, Locale.ENGLISH);
    }

    public Integer getPendingPaymentsCount(int eventId) {
        return getTicketReservationRepository().findAllReservationsWaitingForPaymentCountInEventId(eventId);
    }

    public List<TicketReservation> findAllInvoices(int eventId) {
        return getTicketReservationRepository().findAllReservationsWithInvoices(eventId);
    }

    public Integer countInvoices(int eventId) {
        return getTicketReservationRepository().countInvoices(eventId);
    }


    public boolean hasPaidSupplements(String reservationId) {
        return getAdditionalServiceItemRepository().hasPaidSupplements(reservationId);
    }

    void revertTicketsToFreeIfAccessRestricted(int eventId) {
        List<Integer> restrictedCategories = getTicketCategoryRepository().findByEventId(eventId).stream()
            .filter(TicketCategory::isAccessRestricted)
            .map(TicketCategory::getId)
            .collect(toList());
        if(!restrictedCategories.isEmpty()) {
            int count = getTicketRepository().revertToFreeForRestrictedCategories(eventId, restrictedCategories);
            if(count > 0) {
                log.debug("reverted {} tickets for categories {}", count, restrictedCategories);
            }
        }
    }
}
