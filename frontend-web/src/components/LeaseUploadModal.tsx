import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { X, Gift, Upload, CheckCircle2, FileText, CreditCard } from 'lucide-react';

interface LeaseUploadModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const LeaseUploadModal: React.FC<LeaseUploadModalProps> = ({ isOpen, onClose }) => {
  const [fileSelected, setFileSelected] = useState(false);
  const [upiId, setUpiId] = useState('');
  const [submitted, setSubmitted] = useState(false);

  if (!isOpen) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    setTimeout(() => {
      onClose();
      setSubmitted(false);
      setFileSelected(false);
    }, 2500);
  };

  return (
    <AnimatePresence>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.3 }}
        onClick={onClose}
        className="fixed inset-0 z-50 bg-slate-950/70 backdrop-blur-md flex items-start justify-center overflow-y-auto p-3 sm:items-center sm:p-4 perspective-1000"
      >
        <motion.div
          initial={{ opacity: 0, scale: 0.84, rotateX: 14, y: 30 }}
          animate={{ opacity: 1, scale: 1, rotateX: 0, y: 0 }}
          exit={{ opacity: 0, scale: 0.84, rotateX: -14, y: 30 }}
          transition={{ type: 'spring', stiffness: 450, damping: 24 }}
          onClick={(e) => e.stopPropagation()}
          className="relative my-auto max-h-[calc(100dvh-1.5rem)] w-full max-w-md overflow-x-hidden overflow-y-auto rounded-3xl border border-slate-200/90 bg-white p-5 shadow-2xl transform-gpu sm:max-h-[calc(100dvh-2rem)] sm:p-8"
        >
          
          <button
            onClick={onClose}
            className="absolute top-5 right-5 text-slate-400 hover:text-slate-700 p-1.5 rounded-full hover:bg-slate-100 transition-colors z-10"
          >
            <X className="w-5 h-5" />
          </button>

          <AnimatePresence mode="wait">
            {!submitted ? (
              <motion.div
                key="upload-form"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                transition={{ duration: 0.25 }}
              >
                <div className="text-center mb-6">
                  <div className="w-12 h-12 bg-emerald-50 text-emerald-600 rounded-2xl flex items-center justify-center mx-auto mb-3 border border-emerald-200/80 shadow-xs">
                    <Gift className="w-6 h-6 text-emerald-600" />
                  </div>
                  <h3 className="text-2xl font-bold text-slate-900 font-['Outfit',sans-serif]">
                    Claim ₹1,000 Cash-Back
                  </h3>
                  <p className="text-xs text-slate-500 mt-1">
                    Upload your signed rent agreement to receive a direct UPI transfer
                  </p>
                </div>

                <form onSubmit={handleSubmit} className="space-y-4">
                  
                  {/* File Upload Box */}
                  <div>
                    <label className="text-xs font-bold text-slate-700 block mb-1.5 uppercase tracking-wider">
                      Signed Rent Agreement (PDF or Image)
                    </label>
                    <motion.div 
                      whileHover={{ scale: 1.01 }}
                      whileTap={{ scale: 0.99 }}
                      onClick={() => setFileSelected(true)}
                      className={`border-2 border-dashed rounded-2xl p-6 text-center cursor-pointer transition-all ${
                        fileSelected 
                          ? 'border-emerald-500 bg-emerald-50/60 shadow-xs' 
                          : 'border-slate-300 hover:border-emerald-400 bg-slate-50/80 hover:bg-slate-50'
                      }`}
                    >
                      {fileSelected ? (
                        <motion.div 
                          initial={{ opacity: 0, scale: 0.9 }}
                          animate={{ opacity: 1, scale: 1 }}
                          className="flex items-center justify-center gap-2 text-emerald-700 font-bold text-xs"
                        >
                          <FileText className="w-5 h-5 text-emerald-600" />
                          Rent_Agreement_Signed.pdf (Uploaded ✓)
                        </motion.div>
                      ) : (
                        <div>
                          <Upload className="w-8 h-8 text-slate-400 mx-auto mb-2" />
                          <span className="text-xs font-semibold text-slate-700 block">Click to upload document</span>
                          <span className="text-[10px] text-slate-400">PDF, PNG, JPG up to 10MB</span>
                        </div>
                      )}
                    </motion.div>
                  </div>

                  {/* UPI ID Field */}
                  <div>
                    <label className="text-xs font-bold text-slate-700 block mb-1.5 uppercase tracking-wider">
                      Your UPI ID (GPay / PhonePe / Paytm)
                    </label>
                    <div className="relative">
                      <CreditCard className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                      <input
                        type="text"
                        value={upiId}
                        onChange={(e) => setUpiId(e.target.value)}
                        placeholder="mobileNumber@upi"
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-3.5 py-2.5 text-xs font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 transition-all"
                        required
                      />
                    </div>
                  </div>

                  <motion.button
                    whileHover={{ scale: 1.02, y: -1 }}
                    whileTap={{ scale: 0.97 }}
                    transition={{ type: 'spring', stiffness: 450, damping: 25 }}
                    type="submit"
                    className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold py-3.5 rounded-xl text-xs shadow-md shadow-emerald-600/20 transition-all flex items-center justify-center gap-2"
                  >
                    <Gift className="w-4 h-4" />
                    Submit & Process ₹1,000 Payout
                  </motion.button>

                </form>
              </motion.div>
            ) : (
              <motion.div 
                key="success-screen"
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ type: 'spring', stiffness: 400, damping: 25 }}
                className="text-center py-6"
              >
                <motion.div
                  initial={{ scale: 0 }}
                  animate={{ scale: 1 }}
                  transition={{ type: 'spring', stiffness: 500, damping: 20, delay: 0.1 }}
                >
                  <CheckCircle2 className="w-16 h-16 text-emerald-600 mx-auto mb-4" />
                </motion.div>
                <h3 className="text-2xl font-bold text-slate-900 font-['Outfit',sans-serif] mb-1">
                  Agreement Upload Verified!
                </h3>
                <p className="text-xs text-slate-600">
                  ₹1,000 UPI Cashback successfully queued for transfer to <span className="font-mono font-bold text-slate-900">{upiId}</span>.
                </p>
              </motion.div>
            )}
          </AnimatePresence>

        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
};
