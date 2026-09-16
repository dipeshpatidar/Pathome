import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { Home } from './components/Home';
import { NotificationProvider } from './context/NotificationContext';
import { NotificationToastContainer } from './components/NotificationToastContainer';
import { NotificationCenterDrawer } from './components/NotificationCenterDrawer';
import { AppErrorDialog } from './components/AppErrorDialog';

export default function App() {
  return (
    <NotificationProvider>
      <Router>
        <NotificationToastContainer />
        <NotificationCenterDrawer />
        <AppErrorDialog />
        <Routes>
          <Route path="/*" element={<Home />} />
        </Routes>
      </Router>
    </NotificationProvider>
  );
}
