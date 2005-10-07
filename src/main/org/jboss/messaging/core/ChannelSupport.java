/**
 * JBoss, Home of Professional Open Source
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */


package org.jboss.messaging.core;

import org.jboss.logging.Logger;

import javax.transaction.TransactionManager;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import java.util.Iterator;
import java.util.Set;
import java.util.List;
import java.io.Serializable;

/**
 * @author <a href="mailto:ovidiu@jboss.org">Ovidiu Feodorov</a>
 * @version <tt>$Revision$</tt>
 * $Id$
 */
abstract class ChannelSupport implements Channel
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(ChannelSupport.class);

   // Static --------------------------------------------------------
   
   // Attributes ----------------------------------------------------

   protected Serializable channelID;
   protected Router router;
   protected State state;
   protected TransactionManager tm;
   protected PersistenceManager pm;
   protected MessageStore ms;
  // protected Filter selector;


   // Constructors --------------------------------------------------

   protected ChannelSupport(Serializable channelID,
                            MessageStore ms,
                            PersistenceManager pm,
                            TransactionManager tm)
   {
      if (log.isTraceEnabled()) { log.trace("Creating ChannelSupport: " + channelID + " reliable?" + (pm != null)); }

      this.channelID = channelID;
      this.ms = ms;
      this.tm = tm;
      this.pm = pm;
      if (pm == null)
      {
         state = new TransactionalState(this, tm);
      }
      else
      {
         state = new ReliableState(this, pm, tm);
      }
   }


   // Receiver implementation ---------------------------------------

   public Delivery handle(DeliveryObserver sender, Routable r)
   {
      return handleNoTx(sender, r);
   }

   // DeliveryObserver implementation --------------------------

   public void acknowledge(Delivery d)
   {
      acknowledgeNoTx(d);
   }
   

   public boolean cancel(Delivery d) throws Throwable
   {
      // TODO must be done atomically
      if (!state.remove(d))
      {
         return false;
      }

      if (log.isTraceEnabled()) { log.trace(this + " canceled delivery " + d); }

      state.add(d.getReference());
      if (log.isTraceEnabled()) { log.trace(this + " marked message " + d.getReference() + " as undelivered"); }
      return true;
   }

   public void redeliver(Delivery old, Receiver r) throws Throwable
   {
      if (log.isTraceEnabled()) { log.trace(this + " redelivery request for delivery " + old + " by receiver " + r); }

      // TODO must be done atomically
   
      if (state.remove(old))
      {
         if (log.isTraceEnabled()) { log.trace(this + "old delivery was active, canceled it"); }
         
         MessageReference ref = old.getReference();
         
         //FIXME - What if the message is only redelivered for one particular
         //receiver - won't this set it globally?
         if (log.isTraceEnabled()) { log.trace("Setting redelivered to true"); }
         ref.setRedelivered(true);

         Delivery newd = r.handle(this, ref);

         if (newd == null || newd.isDone())
         {
            return;
         }

         // TODO race condition: what if the receiver acknowledges right here v ?
         state.add(newd);
      }
  
   }

   // Distributor implementation ------------------------------------

   public boolean add(Receiver r)
   {
      if (log.isTraceEnabled()) { log.trace("Attempting to add receiver to channel[" + getChannelID() + "]: " + r); }
      
      boolean added = router.add(r);
      if (added)
      {
         deliver();
      }
      return added;
   }

   public boolean remove(Receiver r)
   {
      return router.remove(r);
   }

   public void clear()
   {
      router.clear();
   }

   public boolean contains(Receiver r)
   {
      return router.contains(r);
   }

   public Iterator iterator()
   {
      return router.iterator();
   }

   // Channel implementation ----------------------------------------

   public boolean isTransactional()
   {
      return state.isReliable();
   }

   public boolean isReliable()
   {
      return state.isReliable();
   }

   public MessageStore getMessageStore()
   {
      return ms;
   }

   public List browse()
   {
      return state.browse(null);
   }

   public List browse(Filter f)
   {
      return state.browse(f);
   }


   public void deliver()
   {
      if (log.isTraceEnabled()){ log.trace("attempting to deliver messages"); }

      List messages = state.undelivered(null);
      if (log.isTraceEnabled()){ log.trace("there are " + messages.size() + " messages to deliver"); }
      for(Iterator i = messages.iterator(); i.hasNext(); )
      {

         MessageReference r = (MessageReference)i.next();

         state.remove(r);
         // TODO: if I crash now I could lose a persisent message
         if (log.isTraceEnabled()){ log.trace("removed " + r + " from state"); }
         
         handleNoTx(null, r);
      }
   }


   public Serializable getChannelID()
   {
      return channelID;
   }

   public void close()
   {
      if (state == null)
      {
         return;
      }

      router.clear();
      router = null;
      state.clear();
      state = null;
      channelID = null;
   }

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------
   
   // Protected -----------------------------------------------------
   
   protected MessageReference ref(Routable r)
   {
      MessageReference ref = null;
      
      if (r.isReference())
      {
         return (MessageReference)r;
      }
      else
      {
         //Convert to reference
         try
         {
            ref = ms.reference(r);
            return ref;
         }
         catch (Throwable t)
         {
            try
            {
               log.error("Failed to reference routable", t);
               tm.rollback();             
            }
            catch(SystemException e)
            {
               log.error(this + " failed to rollback state transaction", e);
               
            }
            return null;
         }
      }
   }
   
   // Private -------------------------------------------------------

   private void checkClosed()
   {
      if (state == null)
      {
         throw new IllegalStateException(this + " closed");
      }
   }

   /**
    * I need a separate private method to make sure is not overriden by TransactionalChannelSupport.
    *
    * @param sender - may be null, in which case the returned acknowledgment will probably be ignored.
    */
   private Delivery handleNoTx(DeliveryObserver sender, Routable r)
   {
      checkClosed();
      if (log.isTraceEnabled()){ log.trace("handling non transactionally " + r); }

      if (r == null)
      {
         return null;
      }
      
      MessageReference ref = ref(r);

      Set deliveries = router.handle(this, ref);

      if (deliveries.isEmpty())
      {
         // no receivers, receivers that don't accept the message or broken receivers
         
         if (log.isTraceEnabled()){ log.trace("No deliveries returned for message; there are no receivers"); }
         
         try
         {
            state.add(ref);
            if (log.isTraceEnabled()){ log.trace("Added state"); }

         }
         catch(Throwable t)
         {
            // this channel cannot safely hold a reliable message, so it doesn't accept it
            log.error("Cannot handle the message", t);
            return null;
         }
      }
      else
      {
         // there are receivers

         Transaction crtTx = null;
         try
         {
            crtTx = tm.suspend();

            //tm.begin();
            for (Iterator i = deliveries.iterator(); i.hasNext(); )
            {
               Delivery d = (Delivery)i.next();
               if (!d.isDone())
               {
                  state.add(d);
               }
            }
           // tm.commit();
         }
         catch(Throwable t)
         {
            log.error(this + " cannot manage delivery, passing responsibility to the sender", t);

            // cannot manage this delivery, pass the responsibility to the sender
            // cannot split delivery, because in case of crash, the message must be recoverable
            // from one and only one channel

            // TODO this is untested
            return new CompositeDelivery(sender, deliveries);
         }
         finally
         {
            if (crtTx != null)
            {
               try
               {
                  tm.resume(crtTx);
               }
               catch(Exception e)
               {
                  String msg = this + " failed to resume existing transaction " + crtTx;
                  log.error(msg, e);
                  throw new IllegalStateException(msg);
               }
            }
         }
      }

      // the channel can safely assume responsibility for delivery
      return new SimpleDelivery(true);
   }


   /**
    * I need a separate private method to make sure is not overriden by TransactionalChannelSupport.
    */
   private void acknowledgeNoTx(Delivery d)
   {
      checkClosed();

      if (log.isTraceEnabled()){ log.trace("acknowledging non transactionally " + d); }

      try
      {
         if (state.remove(d))
         {
            if (log.isTraceEnabled()) { log.trace(this + " delivery " + d + " completed and forgotten"); }
         }
      }
      catch(Throwable t)
      {
         // a non transactional remove shound't throw any transaction
         log.error(this + " failed to remove delivery", t);
      }
   }

   // Inner classes -------------------------------------------------
}
